package com.example.javaagentmvp.admissionworkflow.execution;

import com.example.javaagentmvp.McpTableCapturingToolCallback;
import com.example.javaagentmvp.admissionworkflow.DefaultAdmissionYear;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.admissionworkflow.filter.MajorTierResultFilter;
import com.example.javaagentmvp.admissionworkflow.format.ScoreTierMajorSupport;
import com.example.javaagentmvp.admissionworkflow.format.ScoreToRankResolver;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.example.javaagentmvp.dbagent.DbAgentTargetRegistry;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Deterministic MCP execution for Chat: IR slots drive tool args (E-6). */
@Service
public class AdmissionQueryMcpExecutor {

    private static final Logger log = LoggerFactory.getLogger(AdmissionQueryMcpExecutor.class);

    private final ToolCallback majorByRankToolCallback;
    private final ToolCallback majorByScoreRawCallback;
    private final ToolCallback rankToolCallback;
    private final ObjectMapper objectMapper;
    private final McpTableExtractor mcpTableExtractor;
    private final RagProperties ragProperties;

    @Autowired
    public AdmissionQueryMcpExecutor(
            DbAgentTargetRegistry dbAgentTargetRegistry,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper,
            RagProperties ragProperties) {
        List<ToolCallback> raw = SyncMcpToolCallbackProvider.syncToolCallbacks(dbAgentTargetRegistry.chatMcpClients());
        List<ToolCallback> wrapped = wrapCallbacks(raw, mcpTableExtractor, objectMapper);
        this.majorByRankToolCallback = findTool(wrapped, "getMajorByRank");
        this.majorByScoreRawCallback = findRawTool(raw, "getMajorByScore", "getMajorByRank");
        this.rankToolCallback = findTool(wrapped, "getRankByScore");
        this.objectMapper = objectMapper;
        this.mcpTableExtractor = mcpTableExtractor;
        this.ragProperties = ragProperties;
    }

    AdmissionQueryMcpExecutor(
            ToolCallback majorByScoreRawCallback,
            ToolCallback majorByRankToolCallback,
            ToolCallback rankToolCallback,
            ObjectMapper objectMapper,
            McpTableExtractor mcpTableExtractor,
            RagProperties ragProperties) {
        this.majorByRankToolCallback = majorByRankToolCallback;
        this.majorByScoreRawCallback = majorByScoreRawCallback;
        this.rankToolCallback = rankToolCallback;
        this.objectMapper = objectMapper;
        this.mcpTableExtractor = mcpTableExtractor;
        this.ragProperties = ragProperties == null ? emptyRagProperties() : ragProperties;
    }

    /** Backward-compatible test constructor. */
    AdmissionQueryMcpExecutor(
            ToolCallback majorByScoreRawCallback,
            ToolCallback majorByRankToolCallback,
            ToolCallback rankToolCallback,
            ObjectMapper objectMapper) {
        this(
                majorByScoreRawCallback,
                majorByRankToolCallback,
                rankToolCallback,
                objectMapper,
                new McpTableExtractor(objectMapper),
                emptyRagProperties());
    }

    private static List<ToolCallback> wrapCallbacks(
            List<ToolCallback> rawCallbacks,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        return McpTableCapturingToolCallback.wrapAll(rawCallbacks, mcpTableExtractor, objectMapper);
    }

    public boolean requiresForcedMcp(AdmissionQueryIr query) {
        if (query == null || query.blocksMcpExecution()) {
            return false;
        }
        AdmissionIntent intent = query.toIntent();
        return intent == AdmissionIntent.SCORE
                || intent == AdmissionIntent.RANK
                || intent == AdmissionIntent.REPORT;
    }

    public ExecutionResult execute(AdmissionQueryIr query) {
        if (!requiresForcedMcp(query)) {
            return ExecutionResult.skipped();
        }
        AdmissionQueryContext.set(query);
        AdmissionIntent intent = query.toIntent();
        try {
            String response = switch (intent) {
                case RANK -> rankToolCallback.call(buildRankInput(query));
                case SCORE, REPORT -> executeMajorQuery(query);
                default -> null;
            };
            if (response == null) {
                return ExecutionResult.skipped();
            }
            int tableCount = McpTableContext.tables().size();
            log.info(
                    "AdmissionQueryMcpExecutor: task={} score={} rank={} provinces={} tables={}",
                    query.task(),
                    query.slots().score(),
                    query.slots().rank(),
                    query.slots().provincesOrEmpty(),
                    tableCount);
            return ExecutionResult.executed(query.task(), response, tableCount);
        }
        catch (RuntimeException ex) {
            log.warn("AdmissionQueryMcpExecutor failed task={}: {}", query.task(), ex.getMessage());
            return ExecutionResult.failed(query.task(), ex.getMessage());
        }
    }

    private String executeMajorQuery(AdmissionQueryIr query) {
        AdmissionSlotsIr slots = query.slots();
        if (slots.rank() != null) {
            return finalizeMajorResponse(
                    query,
                    slots.rank(),
                    singleProvince(slots),
                    majorByRankToolCallback.call(buildMajorByRankInput(query, slots.rank(), singleProvince(slots))));
        }
        if (slots.score() == null) {
            throw new IllegalStateException("score or rank required for major query");
        }
        List<String> provinces = slots.provincesOrEmpty();
        if (provinces.size() <= 1) {
            String province = singleProvince(slots);
            int rank = resolveRankFromScore(query, province);
            String majorResponse = majorByRankToolCallback.call(buildMajorByRankInput(query, rank, province));
            return finalizeMajorResponse(query, rank, province, majorResponse);
        }
        return executeMultiProvinceMajorViaRank(query, provinces);
    }

    private String finalizeMajorResponse(
            AdmissionQueryIr query,
            int resolvedRank,
            String province,
            String majorByRankResponse) {
        JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(majorByRankResponse).orElse(null);
        String response = majorByRankResponse;
        if (shouldFallbackToScoreTiers(query, root)) {
            response = fallbackToScoreTiers(query, resolvedRank, province, root).orElse(majorByRankResponse);
            root = mcpTableExtractor.parseMajorByScoreRoot(response).orElse(root);
        }
        return applyIncludeFilters(query, resolvedRank, province, root, response, 3);
    }

    private String applyIncludeFilters(
            AdmissionQueryIr query,
            int resolvedRank,
            String province,
            JsonNode root,
            String originalResponse,
            int tierTablesToReplace) {
        if (root == null || !MajorTierResultFilter.needsFiltering(query, ragProperties)) {
            return originalResponse;
        }
        ObjectNode filtered = MajorTierResultFilter.filter(root, query, ragProperties, objectMapper);
        String tierTableInput = query.slots().score() != null
                ? buildScoreTierTableInput(query, province)
                : buildMajorByRankInput(query, resolvedRank, province);
        if (tierTablesToReplace > 0) {
            McpTableContext.removeLastTables(tierTablesToReplace);
        }
        ScoreTierMajorSupport.captureScoreTierTables(tierTableInput, filtered, mcpTableExtractor);
        log.info(
                "AdmissionQueryMcpExecutor: include filters schools={} majors={} tierCounts=冲{} 稳{} 保{}",
                query.filters().includeSchools(),
                query.filters().includeMajorKeywords(),
                filtered.path("tier_counts").path("冲").asInt(0),
                filtered.path("tier_counts").path("稳").asInt(0),
                filtered.path("tier_counts").path("保").asInt(0));
        try {
            return objectMapper.writeValueAsString(filtered);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize filtered tier response", ex);
        }
    }

    private static RagProperties emptyRagProperties() {
        return new RagProperties(
                false,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "",
                4,
                0.7,
                false,
                "",
                new RagProperties.Routing(List.of(), List.of()),
                new RagProperties.Admissions(false, List.of(), 4, 12, List.of(), ""),
                new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }

    private boolean shouldFallbackToScoreTiers(AdmissionQueryIr query, JsonNode rankRoot) {
        if (MajorTierResultFilter.needsFiltering(query, ragProperties)) {
            return false;
        }
        return query.slots().score() != null && ScoreTierMajorSupport.isSparseRankResult(rankRoot);
    }

    private Optional<String> fallbackToScoreTiers(
            AdmissionQueryIr query,
            int resolvedRank,
            String province,
            JsonNode sparseRankRoot) {
        if (majorByScoreRawCallback == null) {
            return Optional.empty();
        }
        AdmissionSlotsIr slots = query.slots();
        int userScore = slots.score();
        McpTableContext.removeLastTables(3);
        String scoreResponse = majorByScoreRawCallback.call(buildMajorByScoreInput(query, province));
        String tierTableInput = buildScoreTierTableInput(query, province);
        Optional<ObjectNode> tiered = ScoreTierMajorSupport.fallbackFromMajorByScoreResponse(
                userScore,
                scoreResponse,
                resolvedRank,
                tierTableInput,
                objectMapper,
                mcpTableExtractor);
        if (tiered.isEmpty()) {
            restoreSparseRankTables(sparseRankRoot, buildMajorByRankInput(query, resolvedRank, province));
            return Optional.empty();
        }
        log.info(
                "AdmissionQueryMcpExecutor: sparse rank tiers (冲={} 稳={} 保={}), fell back to score tiers (冲={} 稳={} 保={})",
                tierCount(sparseRankRoot, "冲"),
                tierCount(sparseRankRoot, "稳"),
                tierCount(sparseRankRoot, "保"),
                tiered.get().path("tier_counts").path("冲").asInt(0),
                tiered.get().path("tier_counts").path("稳").asInt(0),
                tiered.get().path("tier_counts").path("保").asInt(0));
        try {
            return Optional.of(objectMapper.writeValueAsString(tiered.get()));
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize score-tier fallback response", ex);
        }
    }

    private void restoreSparseRankTables(JsonNode rankRoot, String toolInput) {
        if (rankRoot == null) {
            return;
        }
        JsonNode byTier = rankRoot.get("majors_by_tier");
        if (byTier == null || !byTier.isObject()) {
            return;
        }
        for (String tier : List.of("冲", "稳", "保")) {
            JsonNode majors = byTier.get(tier);
            if (majors != null && majors.isArray()) {
                mcpTableExtractor.extractMajorByScoreFromMajors(toolInput, majors, tier)
                        .ifPresent(McpTableContext::add);
            }
        }
    }

    private static int tierCount(JsonNode root, String tier) {
        if (root == null) {
            return 0;
        }
        JsonNode tierCounts = root.get("tier_counts");
        if (tierCounts != null && tierCounts.has(tier)) {
            return tierCounts.path(tier).asInt(0);
        }
        return root.path("majors_by_tier").path(tier).size();
    }

    private String executeMultiProvinceMajorViaRank(AdmissionQueryIr query, List<String> provinces) {
        AdmissionSlotsIr slots = query.slots();
        ArrayNode allMajors = objectMapper.createArrayNode();
        ObjectNode mergedByTier = objectMapper.createObjectNode();
        mergedByTier.set("冲", objectMapper.createArrayNode());
        mergedByTier.set("稳", objectMapper.createArrayNode());
        mergedByTier.set("保", objectMapper.createArrayNode());
        String referenceResponse = null;

        for (String province : provinces) {
            int rank = resolveRankFromScore(query, province);
            String majorResponse = majorByRankToolCallback.call(buildMajorByRankInput(query, rank, province));
            referenceResponse = majorResponse;
            JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(majorResponse).orElse(null);
            if (root == null) {
                continue;
            }
            JsonNode majorsNode = root.get("majors");
            if (majorsNode != null && majorsNode.isArray()) {
                for (JsonNode major : majorsNode) {
                    ObjectNode tagged = major.deepCopy();
                    tagged.put("query_province", province);
                    allMajors.add(tagged);
                }
            }
            mergeTierArrays(mergedByTier, root.get("majors_by_tier"), province);
        }

        if (allMajors.isEmpty()) {
            return referenceResponse == null ? "{}" : referenceResponse;
        }

        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("user_score", slots.score());
        merged.set("majors", allMajors);
        merged.put("count", allMajors.size());
        merged.set("majors_by_tier", mergedByTier);
        ObjectNode tierCounts = objectMapper.createObjectNode();
        tierCounts.put("冲", mergedByTier.get("冲").size());
        tierCounts.put("稳", mergedByTier.get("稳").size());
        tierCounts.put("保", mergedByTier.get("保").size());
        merged.set("tier_counts", tierCounts);
        try {
            String mergedJson = objectMapper.writeValueAsString(merged);
            int rank = resolveRankFromScore(query, provinces.get(0));
            return applyIncludeFilters(
                    query,
                    rank,
                    provinces.get(0),
                    merged,
                    mergedJson,
                    provinces.size() * 3);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to merge multi-province rank-major response", ex);
        }
    }

    private void mergeTierArrays(ObjectNode target, JsonNode sourceByTier, String province) {
        if (sourceByTier == null || !sourceByTier.isObject()) {
            return;
        }
        for (String tier : List.of("冲", "稳", "保")) {
            JsonNode tierMajors = sourceByTier.get(tier);
            if (tierMajors == null || !tierMajors.isArray()) {
                continue;
            }
            ArrayNode bucket = (ArrayNode) target.get(tier);
            for (JsonNode major : tierMajors) {
                ObjectNode tagged = major.deepCopy();
                tagged.put("query_province", province);
                bucket.add(tagged);
            }
        }
    }

    private int resolveRankFromScore(AdmissionQueryIr query, String province) {
        String rankResponse = rankToolCallback.call(buildRankInputForProvince(query, province));
        JsonNode rankRoot = mcpTableExtractor.parseMajorByScoreRoot(rankResponse)
                .orElseThrow(() -> new IllegalStateException("Invalid getRankByScore response"));
        AdmissionSlotsIr slots = query.slots();
        return ScoreToRankResolver.resolveRank(
                        rankRoot,
                        DefaultAdmissionYear.resolve(slots.year()),
                        slots.subjectGroup())
                .orElseThrow(() -> new IllegalStateException(
                        "无法将 " + slots.score() + " 分转换为位次，请补充省份、科类或年份"));
    }

    private String buildMajorByRankInput(AdmissionQueryIr query, int rank, String province) {
        AdmissionSlotsIr slots = query.slots();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("rank", rank);
        if (province != null && !province.isBlank()) {
            args.put("province", province);
        }
        appendOptionalSlots(args, slots);
        if (slots.score() != null) {
            args.put("user_score", slots.score());
        }
        return args.toString();
    }

    private String buildMajorByRankInput(AdmissionQueryIr query) {
        return buildMajorByRankInput(query, query.slots().rank(), singleProvince(query.slots()));
    }

    private String buildMajorByScoreInput(AdmissionQueryIr query, String province) {
        AdmissionSlotsIr slots = query.slots();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("score", ScoreTierMajorSupport.mcpQueryScore(slots.score()));
        if (province != null && !province.isBlank()) {
            args.put("province", province);
        }
        appendOptionalSlots(args, slots);
        return args.toString();
    }

    private String buildScoreTierTableInput(AdmissionQueryIr query, String province) {
        AdmissionSlotsIr slots = query.slots();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("score", slots.score());
        if (province != null && !province.isBlank()) {
            args.put("province", province);
        }
        appendOptionalSlots(args, slots);
        return args.toString();
    }

    private String buildRankInput(AdmissionQueryIr query) {
        return buildRankInputForProvince(query, singleProvince(query.slots()));
    }

    private String buildRankInputForProvince(AdmissionQueryIr query, String province) {
        AdmissionSlotsIr slots = query.slots();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("score", slots.score());
        if (province != null && !province.isBlank()) {
            args.put("province", province);
        }
        appendOptionalSlots(args, slots);
        return args.toString();
    }

    private static String singleProvince(AdmissionSlotsIr slots) {
        List<String> provinces = slots.provincesOrEmpty();
        return provinces.size() == 1 ? provinces.get(0) : null;
    }

    private static void appendOptionalSlots(ObjectNode args, AdmissionSlotsIr slots) {
        if (slots.subjectGroup() != null && !slots.subjectGroup().isBlank()) {
            args.put("subject_group", slots.subjectGroup());
        }
        args.put("year", DefaultAdmissionYear.resolve(slots.year()));
        if (slots.admissionType() != null && !slots.admissionType().isBlank()) {
            args.put("admission_type", slots.admissionType());
        }
    }

    private static ToolCallback findTool(List<ToolCallback> callbacks, String nameFragment) {
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().contains(nameFragment))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP tool not found: " + nameFragment));
    }

    private static ToolCallback findRawTool(List<ToolCallback> callbacks, String nameFragment, String excludeFragment) {
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().contains(nameFragment))
                .filter(callback -> excludeFragment == null
                        || !callback.getToolDefinition().name().contains(excludeFragment))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP tool not found: " + nameFragment));
    }

    public record ExecutionResult(
            boolean attempted,
            boolean success,
            String task,
            String toolResponse,
            int tableCount,
            String errorMessage) {

        static ExecutionResult skipped() {
            return new ExecutionResult(false, false, null, null, 0, null);
        }

        static ExecutionResult executed(String task, String toolResponse, int tableCount) {
            return new ExecutionResult(true, true, task, toolResponse, tableCount, null);
        }

        static ExecutionResult failed(String task, String errorMessage) {
            return new ExecutionResult(true, false, task, null, 0, errorMessage);
        }
    }
}
