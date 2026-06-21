package com.example.javaagentmvp;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionForcedMcpContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.admissionworkflow.filter.MajorScoreFilter;
import com.example.javaagentmvp.admissionworkflow.filter.QueryConstraints;
import com.example.javaagentmvp.admissionworkflow.format.RankResponseFormatter;
import com.example.javaagentmvp.admissionworkflow.format.RankSubjectGroupResolver;
import com.example.javaagentmvp.admissionworkflow.format.ScoreToRankResolver;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.chat.ChatTurnFlowLog;
import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpRankContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Optional;

/** Wraps MCP {@link ToolCallback} to capture structured tables for UI clients. */
public final class McpTableCapturingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(McpTableCapturingToolCallback.class);

    static final int SCORE_TIER_DELTA = 15;
    private static final int MIN_SCORE = 200;
    private static final int MAX_SCORE = 750;

    private static final String TIER_REACH = "冲";
    private static final String TIER_STEADY = "稳";
    private static final String TIER_SAFE = "保";

    private final ToolCallback delegate;
    private final ToolCallback rankDelegate;
    private final ToolCallback majorByRankDelegate;
    private final McpTableExtractor mcpTableExtractor;
    private final ObjectMapper objectMapper;

    private McpTableCapturingToolCallback(
            ToolCallback delegate,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper,
            ToolCallback rankDelegate,
            ToolCallback majorByRankDelegate) {
        this.delegate = delegate;
        this.mcpTableExtractor = mcpTableExtractor;
        this.objectMapper = objectMapper;
        this.rankDelegate = rankDelegate;
        this.majorByRankDelegate = majorByRankDelegate;
    }

    public static ToolCallback wrap(
            ToolCallback delegate,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        return wrap(delegate, mcpTableExtractor, objectMapper, null, null);
    }

    public static ToolCallback wrap(
            ToolCallback delegate,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper,
            ToolCallback rankDelegate,
            ToolCallback majorByRankDelegate) {
        if (delegate instanceof McpTableCapturingToolCallback) {
            return delegate;
        }
        return new McpTableCapturingToolCallback(
                delegate, mcpTableExtractor, objectMapper, rankDelegate, majorByRankDelegate);
    }

    public static List<ToolCallback> wrapAll(
            List<ToolCallback> callbacks,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        ToolCallback rankDelegate = findRawCallback(callbacks, "getRankByScore");
        ToolCallback majorByRankDelegate = findRawCallback(callbacks, "getMajorByRank");
        return callbacks.stream()
                .map(callback -> {
                    if (isGetMajorByScoreName(callback.getToolDefinition().name())) {
                        return wrap(callback, mcpTableExtractor, objectMapper, rankDelegate, majorByRankDelegate);
                    }
                    return wrap(callback, mcpTableExtractor, objectMapper);
                })
                .toList();
    }

    private static ToolCallback findRawCallback(List<ToolCallback> callbacks, String fragment) {
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().contains(fragment))
                .findFirst()
                .orElse(null);
    }

    private static boolean isGetMajorByScoreName(String toolName) {
        return toolName != null && toolName.contains("getMajorByScore") && !toolName.contains("getMajorByRank");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        if (isGetRankByScore(toolName)) {
            return handleGetRankByScore(toolInput, toolContext);
        }
        if (isGetMajorByRank(toolName)) {
            return handleGetMajorByRank(toolInput, toolContext);
        }
        if (!isGetMajorByScore(toolName)) {
            String responseData = delegate.call(toolInput, toolContext);
            mcpTableExtractor.extract(toolName, toolInput, responseData).ifPresent(McpTableContext::add);
            return responseData;
        }

        return handleGetMajorByScore(toolInput, toolContext);
    }

    private String handleGetRankByScore(String toolInput, ToolContext toolContext) {
        if (AdmissionForcedMcpContext.isPreExecuted()) {
            return reusePreExecutedResponse(toolInput, toolContext);
        }
        Optional<AdmissionQueryIr> queryOpt = AdmissionQueryContext.current();
        AdmissionSlotsIr slots = queryOpt.map(AdmissionQueryIr::slots).orElse(null);
        Integer score = resolveRankScore(toolInput, toolContext);
        List<String> provinces = resolveRankProvinces(toolInput, slots);

        if (provinces.size() > 1 && !McpRankContext.multiProvinceFanOutDone()) {
            logMcpStep("multi-province rank score=%s provinces=%s", score, provinces);
            return executeMultiProvinceRankQuery(toolInput, toolContext, score, provinces, slots);
        }

        String province = provinces.size() == 1
                ? provinces.get(0)
                : resolveRankProvince(toolInput, toolContext);
        if (province != null && McpRankContext.hasCapturedProvince(province)) {
            log.info("getRankByScore: skip duplicate province={}", province);
            return cachedRankResponse(province).orElseGet(() -> delegate.call(toolInput, toolContext));
        }
        String queryInput = enrichRankToolInput(
                toolInput,
                score == null ? parseScore(toolInput) : score,
                province,
                slots);
        logMcpStep("getRankByScore score=%s province=%s", score, province);
        String responseData = delegate.call(queryInput, toolContext);
        captureRankResult(queryInput, toolContext, responseData, province);
        return responseData;
    }

    private String executeMultiProvinceRankQuery(
            String toolInput,
            ToolContext toolContext,
            Integer score,
            List<String> provinces,
            AdmissionSlotsIr slots) {
        McpRankContext.markMultiProvinceFanOutDone();
        int queryScore = score == null ? 0 : score;
        if (score == null) {
            Integer parsed = parseScore(toolInput);
            if (parsed != null) {
                queryScore = parsed;
            }
        }

        ArrayNode allRanks = objectMapper.createArrayNode();
        for (String province : provinces) {
            String queryInput = enrichRankToolInput(toolInput, queryScore, province, slots);
            String responseData = delegate.call(queryInput, toolContext);
            JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
            if (root == null || !root.has("ranks") || !root.get("ranks").isArray()) {
                continue;
            }
            captureRankResult(queryInput, toolContext, responseData, province);
            for (JsonNode rank : root.get("ranks")) {
                ObjectNode tagged = rank.deepCopy();
                if (!tagged.hasNonNull("province") || tagged.path("province").asText("").isBlank()) {
                    tagged.put("province", province);
                }
                allRanks.add(tagged);
            }
        }

        if (allRanks.isEmpty()) {
            try {
                ObjectNode empty = objectMapper.createObjectNode();
                empty.put("count", 0);
                empty.set("ranks", objectMapper.createArrayNode());
                return objectMapper.writeValueAsString(empty);
            }
            catch (Exception ex) {
                throw new IllegalStateException("Failed to build empty multi-province rank response", ex);
            }
        }

        try {
            ObjectNode merged = objectMapper.createObjectNode();
            merged.put("count", allRanks.size());
            merged.set("ranks", allRanks);
            return objectMapper.writeValueAsString(merged);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to merge multi-province rank response", ex);
        }
    }

    private List<String> resolveRankProvinces(String toolInput, AdmissionSlotsIr slots) {
        if (slots != null && slots.provincesOrEmpty().size() > 1) {
            return slots.provincesOrEmpty();
        }
        String toolProvince = parseProvince(toolInput);
        if (toolProvince != null && !toolProvince.isBlank()) {
            return List.of(toolProvince);
        }
        if (slots != null && !slots.provincesOrEmpty().isEmpty()) {
            return slots.provincesOrEmpty();
        }
        return List.of();
    }

    private Optional<String> cachedRankResponse(String province) {
        return McpRankContext.findByProvince(province).flatMap(capture -> {
            try {
                return Optional.of(objectMapper.writeValueAsString(capture.rankResult()));
            }
            catch (Exception ex) {
                return Optional.empty();
            }
        });
    }

    private String handleGetMajorByRank(String toolInput, ToolContext toolContext) {
        if (AdmissionForcedMcpContext.isPreExecuted()) {
            return reusePreExecutedResponse(toolInput, toolContext);
        }
        Optional<AdmissionQueryIr> queryOpt = AdmissionQueryContext.current();
        AdmissionSlotsIr slots = queryOpt.map(AdmissionQueryIr::slots).orElse(null);
        Integer userRank = resolveUserRank(toolInput, toolContext);
        if (userRank == null) {
            String responseData = delegate.call(toolInput, toolContext);
            captureMajorByRankTierTables(toolInput, responseData);
            return responseData;
        }

        List<String> provinces = resolveProvinces(toolInput, slots);
        if (provinces.isEmpty()) {
            logMcpStep("single-province rank=%s provinces=from-tool", userRank);
            String enriched = enrichRankMajorToolInput(toolInput, userRank, parseProvince(toolInput), slots);
            String responseData = delegate.call(enriched, toolContext);
            return finalizeMajorByRankResponse(enriched, responseData, queryOpt);
        }
        if (provinces.size() == 1) {
            logMcpStep(
                    "single-province rank=%s province=%s subject=%s",
                    userRank,
                    provinces.get(0),
                    slots == null ? null : slots.subjectGroup());
            String enriched = enrichRankMajorToolInput(toolInput, userRank, provinces.get(0), slots);
            String responseData = delegate.call(enriched, toolContext);
            return finalizeMajorByRankResponse(enriched, responseData, queryOpt);
        }

        logMcpStep(
                "multi-province rank=%s provinces=%s subject=%s",
                userRank,
                provinces,
                slots == null ? null : slots.subjectGroup());
        return executeMultiProvinceMajorByRankQuery(toolInput, toolContext, userRank, provinces, slots, queryOpt);
    }

    private String executeMultiProvinceMajorByRankQuery(
            String toolInput,
            ToolContext toolContext,
            int userRank,
            List<String> provinces,
            AdmissionSlotsIr slots,
            Optional<AdmissionQueryIr> queryOpt) {
        ArrayNode allMajors = objectMapper.createArrayNode();
        ObjectNode mergedByTier = objectMapper.createObjectNode();
        mergedByTier.set(TIER_REACH, objectMapper.createArrayNode());
        mergedByTier.set(TIER_STEADY, objectMapper.createArrayNode());
        mergedByTier.set(TIER_SAFE, objectMapper.createArrayNode());
        String referenceInput = toolInput;

        for (String province : provinces) {
            String enrichedInput = enrichRankMajorToolInput(toolInput, userRank, province, slots);
            referenceInput = enrichedInput;
            String responseData = delegate.call(enrichedInput, toolContext);
            JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
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
            mergeRankTierArrays(mergedByTier, root.get("majors_by_tier"), province);
        }

        if (allMajors.isEmpty()) {
            String responseData = delegate.call(toolInput, toolContext);
            captureMajorByRankTierTables(toolInput, responseData);
            return responseData;
        }

        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("user_rank", userRank);
        merged.set("majors", allMajors);
        merged.put("count", allMajors.size());
        merged.set("majors_by_tier", mergedByTier);
        ObjectNode tierCounts = objectMapper.createObjectNode();
        tierCounts.put(TIER_REACH, mergedByTier.get(TIER_REACH).size());
        tierCounts.put(TIER_STEADY, mergedByTier.get(TIER_STEADY).size());
        tierCounts.put(TIER_SAFE, mergedByTier.get(TIER_SAFE).size());
        merged.set("tier_counts", tierCounts);
        captureMajorByRankTierTables(referenceInput, writeJson(merged));
        return writeJson(merged);
    }

    private void mergeRankTierArrays(ObjectNode target, JsonNode sourceByTier, String province) {
        if (sourceByTier == null || !sourceByTier.isObject()) {
            return;
        }
        for (String tier : List.of(TIER_REACH, TIER_STEADY, TIER_SAFE)) {
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

    private String finalizeMajorByRankResponse(
            String toolInput,
            String responseData,
            Optional<AdmissionQueryIr> queryOpt) {
        return finalizeMajorByRankResponse(toolInput, responseData, queryOpt, true);
    }

    private String finalizeMajorByRankResponse(
            String toolInput,
            String responseData,
            Optional<AdmissionQueryIr> queryOpt,
            boolean captureTierTables) {
        JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
        if (root == null) {
            return responseData;
        }
        if (captureTierTables) {
            captureMajorByRankTierTables(toolInput, writeJson(root));
        }
        return writeJson(root);
    }

    private void captureMajorByRankTierTables(String toolInput, String responseData) {
        JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
        if (root == null) {
            return;
        }
        JsonNode byTier = root.get("majors_by_tier");
        if (byTier != null && byTier.isObject()) {
            for (String tier : List.of(TIER_REACH, TIER_STEADY, TIER_SAFE)) {
                JsonNode majors = byTier.get(tier);
                if (majors != null && majors.isArray()) {
                    captureTierTable(toolInput, tier, copyMajorsArray(majors));
                }
            }
            return;
        }
        JsonNode majorsNode = root.get("majors");
        if (majorsNode != null && majorsNode.isArray()) {
            captureTierTable(toolInput, null, copyMajorsArray(majorsNode));
        }
    }

    private ArrayNode copyMajorsArray(JsonNode majorsNode) {
        return mcpTableExtractor.copyMajorsArray(majorsNode);
    }

    private String enrichRankMajorToolInput(String toolInput, int rank, String province, AdmissionSlotsIr slots) {
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            ObjectNode updated = node.deepCopy();
            updated.put("rank", rank);
            updated.remove("score");
            if (province != null && !province.isBlank()) {
                updated.put("province", province);
            }
            if (slots != null) {
                if (slots.subjectGroup() != null && !slots.subjectGroup().isBlank()) {
                    updated.put("subject_group", slots.subjectGroup());
                }
                if (slots.year() != null) {
                    updated.put("year", slots.year());
                }
                if (slots.admissionType() != null && !slots.admissionType().isBlank()) {
                    updated.put("admission_type", slots.admissionType());
                }
            }
            return objectMapper.writeValueAsString(updated);
        }
        catch (Exception ex) {
            return toolInput;
        }
    }

    private Integer resolveUserRank(String toolInput, ToolContext toolContext) {
        Optional<Integer> fromQuery = AdmissionQueryContext.current()
                .map(query -> query.slots().rank());
        if (fromQuery.isPresent()) {
            return fromQuery.get();
        }
        Integer toolRank = parseRank(toolInput);
        Optional<Integer> parsedFromMessage = parseRankFromUserMessage(toolContext);
        return parsedFromMessage.orElse(toolRank);
    }

    private static Optional<Integer> parseRankFromUserMessage(ToolContext toolContext) {
        if (toolContext == null) {
            return Optional.empty();
        }
        List<Message> history = toolContext.getToolCallHistory();
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message instanceof UserMessage userMessage) {
                return AdmissionInputParser.parseRank(userMessage.getText());
            }
        }
        return Optional.empty();
    }

    private Integer parseRank(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            JsonNode rankNode = node.get("rank");
            if (rankNode == null || !rankNode.isNumber()) {
                return null;
            }
            return rankNode.intValue();
        }
        catch (Exception ex) {
            return null;
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize MCP response", ex);
        }
    }

    private String handleGetMajorByScore(String toolInput, ToolContext toolContext) {
        if (AdmissionForcedMcpContext.isPreExecuted()) {
            return reusePreExecutedResponse(toolInput, toolContext);
        }
        Integer llmScore = parseScore(toolInput);
        Optional<Integer> baseScoreOpt = resolveUserScore(toolContext, llmScore);
        if (baseScoreOpt.isEmpty()) {
            String responseData = delegate.call(toolInput, toolContext);
            mcpTableExtractor.extract(delegate.getToolDefinition().name(), toolInput, responseData)
                    .ifPresent(McpTableContext::add);
            return responseData;
        }

        int baseScore = baseScoreOpt.get();
        if (rankDelegate == null || majorByRankDelegate == null) {
            log.warn("getMajorByScore: rank/majorByRank delegates unavailable, calling getMajorByScore directly");
            return delegate.call(toolInput, toolContext);
        }

        Optional<AdmissionQueryIr> queryOpt = AdmissionQueryContext.current();
        AdmissionSlotsIr slots = queryOpt.map(AdmissionQueryIr::slots).orElse(null);
        List<String> provinces = resolveProvinces(toolInput, slots);
        logMcpStep("score-to-rank major score=%d provinces=%s", baseScore, provinces);

        if (provinces.size() <= 1) {
            String province = provinces.isEmpty() ? parseProvince(toolInput) : provinces.get(0);
            int rank = resolveRankFromScore(baseScore, province, slots, toolContext);
            String enriched = enrichRankMajorToolInput(toolInput, rank, province, slots);
            return invokeMajorByRank(enriched, toolContext, queryOpt, true);
        }
        return executeMultiProvinceScoreToRankMajor(
                toolInput, toolContext, baseScore, provinces, slots, queryOpt);
    }

    private String executeMultiProvinceScoreToRankMajor(
            String toolInput,
            ToolContext toolContext,
            int baseScore,
            List<String> provinces,
            AdmissionSlotsIr slots,
            Optional<AdmissionQueryIr> queryOpt) {
        ArrayNode allMajors = objectMapper.createArrayNode();
        ObjectNode mergedByTier = objectMapper.createObjectNode();
        mergedByTier.set(TIER_REACH, objectMapper.createArrayNode());
        mergedByTier.set(TIER_STEADY, objectMapper.createArrayNode());
        mergedByTier.set(TIER_SAFE, objectMapper.createArrayNode());
        String referenceInput = toolInput;

        for (String province : provinces) {
            int rank = resolveRankFromScore(baseScore, province, slots, toolContext);
            String enriched = enrichRankMajorToolInput(toolInput, rank, province, slots);
            referenceInput = enriched;
            String responseData = invokeMajorByRank(enriched, toolContext, queryOpt, false);
            JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
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
            mergeRankTierArrays(mergedByTier, root.get("majors_by_tier"), province);
        }

        if (allMajors.isEmpty()) {
            return invokeMajorByRank(referenceInput, toolContext, queryOpt, true);
        }

        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("user_score", baseScore);
        merged.set("majors", allMajors);
        merged.put("count", allMajors.size());
        merged.set("majors_by_tier", mergedByTier);
        ObjectNode tierCounts = objectMapper.createObjectNode();
        tierCounts.put(TIER_REACH, mergedByTier.get(TIER_REACH).size());
        tierCounts.put(TIER_STEADY, mergedByTier.get(TIER_STEADY).size());
        tierCounts.put(TIER_SAFE, mergedByTier.get(TIER_SAFE).size());
        merged.set("tier_counts", tierCounts);
        captureMajorByRankTierTables(referenceInput, writeJson(merged));
        return writeJson(merged);
    }

    private int resolveRankFromScore(
            int score,
            String province,
            AdmissionSlotsIr slots,
            ToolContext toolContext) {
        String rankInput = enrichRankToolInput("{}", score, province, slots);
        String rankResponse = rankDelegate.call(rankInput, toolContext);
        JsonNode rankRoot = mcpTableExtractor.parseMajorByScoreRoot(rankResponse)
                .orElseThrow(() -> new IllegalStateException("Invalid getRankByScore response"));
        return ScoreToRankResolver.resolveRank(
                        rankRoot,
                        slots == null ? null : slots.year(),
                        slots == null ? null : slots.subjectGroup())
                .orElseThrow(() -> new IllegalStateException(
                        "无法将 " + score + " 分转换为位次，请补充省份、科类或年份"));
    }

    private String invokeMajorByRank(
            String toolInput,
            ToolContext toolContext,
            Optional<AdmissionQueryIr> queryOpt) {
        return invokeMajorByRank(toolInput, toolContext, queryOpt, true);
    }

    private String invokeMajorByRank(
            String toolInput,
            ToolContext toolContext,
            Optional<AdmissionQueryIr> queryOpt,
            boolean captureTierTables) {
        if (AdmissionForcedMcpContext.isPreExecuted()) {
            return reusePreExecutedResponse(toolInput, toolContext);
        }
        Integer userRank = resolveUserRank(toolInput, toolContext);
        if (userRank == null) {
            String responseData = majorByRankDelegate.call(toolInput, toolContext);
            if (captureTierTables) {
                captureMajorByRankTierTables(toolInput, responseData);
            }
            return responseData;
        }
        List<String> provinces = resolveMajorRankProvinces(toolInput, queryOpt.map(AdmissionQueryIr::slots).orElse(null));
        if (provinces.size() <= 1) {
            String enriched = provinces.isEmpty()
                    ? enrichRankMajorToolInput(toolInput, userRank, parseProvince(toolInput), null)
                    : enrichRankMajorToolInput(toolInput, userRank, provinces.get(0), queryOpt.map(AdmissionQueryIr::slots).orElse(null));
            String responseData = majorByRankDelegate.call(enriched, toolContext);
            return finalizeMajorByRankResponse(enriched, responseData, queryOpt, captureTierTables);
        }
        AdmissionSlotsIr slots = queryOpt.map(AdmissionQueryIr::slots).orElse(null);
        return executeMultiProvinceMajorByRankQuery(toolInput, toolContext, userRank, provinces, slots, queryOpt);
    }

    private String reusePreExecutedResponse(String toolInput, ToolContext toolContext) {
        Optional<String> cached = AdmissionForcedMcpContext.lastToolResponse();
        if (cached.isPresent()) {
            log.info("{}: skip duplicate call, MCP pre-executed", delegate.getToolDefinition().name());
            return cached.get();
        }
        return delegate.call(toolInput, toolContext);
    }

    private static void logMcpStep(String detailFormat, Object... args) {
        if (!ChatTurnFlowLog.active()) {
            return;
        }
        ChatTurnFlowLog.step(ChatTurnFlowLog.Step.MCP_PROCESS, detailFormat, args);
    }

    private String executeSingleProvinceMajorQuery(
            String toolInput,
            ToolContext toolContext,
            String toolName,
            int baseScore,
            AdmissionSlotsIr slots,
            Optional<AdmissionQueryIr> queryOpt) {
        String userScoreInput = withScore(toolInput, baseScore);
        String queryInput = withScore(userScoreInput, clampScore(baseScore + SCORE_TIER_DELTA));
        String responseData = delegate.call(queryInput, toolContext);
        JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
        if (root == null) {
            return responseData;
        }

        JsonNode processed = applyConstraintsIfNeeded(root, baseScore, queryOpt);
        logConstraintFilter(root, processed, queryOpt);
        JsonNode majorsNode = processed.get("majors");
        if (majorsNode == null || !majorsNode.isArray()) {
            return responseData;
        }

        return buildTieredResponseFromMajors(userScoreInput, baseScore, majorsNode);
    }

    private String executeMultiProvinceMajorQuery(
            String toolInput,
            ToolContext toolContext,
            String toolName,
            int baseScore,
            List<String> provinces,
            AdmissionSlotsIr slots,
            Optional<AdmissionQueryIr> queryOpt) {
        ArrayNode allMajors = objectMapper.createArrayNode();
        String referenceInput = toolInput;
        for (String province : provinces) {
            String enrichedInput = enrichToolInput(toolInput, baseScore, province, slots);
            referenceInput = enrichedInput;
            String queryInput = withScore(enrichedInput, clampScore(baseScore + SCORE_TIER_DELTA));
            String responseData = delegate.call(queryInput, toolContext);
            JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
            if (root == null) {
                continue;
            }
            JsonNode majorsNode = root.get("majors");
            if (majorsNode == null || !majorsNode.isArray()) {
                continue;
            }
            for (JsonNode major : majorsNode) {
                ObjectNode tagged = major.deepCopy();
                tagged.put("query_province", province);
                allMajors.add(tagged);
            }
        }

        if (allMajors.isEmpty()) {
            String responseData = delegate.call(toolInput, toolContext);
            mcpTableExtractor.extract(toolName, toolInput, responseData).ifPresent(McpTableContext::add);
            return responseData;
        }

        ObjectNode merged = objectMapper.createObjectNode();
        merged.set("majors", allMajors);
        merged.put("count", allMajors.size());
        JsonNode processed = applyConstraintsIfNeeded(merged, baseScore, queryOpt);
        logConstraintFilter(merged, processed, queryOpt);
        JsonNode majorsNode = processed.get("majors");
        if (majorsNode == null || !majorsNode.isArray()) {
            return buildTieredResponse(baseScore, new TierBuckets(
                    objectMapper.createArrayNode(),
                    objectMapper.createArrayNode(),
                    objectMapper.createArrayNode()));
        }
        String userScoreInput = withScore(referenceInput, baseScore);
        return buildTieredResponseFromMajors(userScoreInput, baseScore, majorsNode);
    }

    private void logConstraintFilter(JsonNode before, JsonNode after, Optional<AdmissionQueryIr> queryOpt) {
        if (!ChatTurnFlowLog.active() || queryOpt.isEmpty()) {
            return;
        }
        QueryConstraints constraints = QueryConstraints.fromIr(queryOpt.get(), null);
        if (!needsConstraintFilter(constraints)) {
            return;
        }
        int beforeCount = before == null ? 0 : before.path("majors").size();
        int afterCount = after == null ? 0 : after.path("majors").size();
        ChatTurnFlowLog.step(
                ChatTurnFlowLog.Step.MCP_PROCESS,
                "constraints applied before=%d after=%d excludeSchool=%s excludeMajor=%s provinces=%s majorGroups=%s disciplineCategories=%s",
                beforeCount,
                afterCount,
                constraints.excludeSchoolNameContains(),
                constraints.excludeMajorKeywords(),
                constraints.provinces(),
                constraints.includeMajorDisciplineGroups(),
                constraints.includeDisciplineCategories());
    }

    private JsonNode applyConstraintsIfNeeded(JsonNode root, int baseScore, Optional<AdmissionQueryIr> queryOpt) {
        if (queryOpt.isEmpty()) {
            return root;
        }
        QueryConstraints constraints = QueryConstraints.fromIr(queryOpt.get(), null);
        if (!needsConstraintFilter(constraints)) {
            return root;
        }
        AdmissionQueryHints.Hints hints = new AdmissionQueryHints.Hints(List.of(), List.of(), false, false);
        MajorScoreFilter.FilterResult filtered = MajorScoreFilter.filter(root, baseScore, hints, constraints, objectMapper);
        return filtered.payload();
    }

    private static boolean needsConstraintFilter(QueryConstraints constraints) {
        return constraints.hasExclusions()
                || constraints.hasProvinceFilter()
                || constraints.hasMajorCategoryFilter()
                || (constraints.includeMajorKeywords() != null && !constraints.includeMajorKeywords().isEmpty());
    }

    private String buildTieredResponseFromMajors(String userScoreInput, int baseScore, JsonNode majorsNode) {
        TierBuckets buckets = classifyMajors(majorsNode, baseScore);
        captureTierTable(userScoreInput, TIER_REACH, buckets.reach());
        captureTierTable(userScoreInput, TIER_STEADY, buckets.steady());
        captureTierTable(userScoreInput, TIER_SAFE, buckets.safe());
        return buildTieredResponse(baseScore, buckets);
    }

    private List<String> resolveMajorRankProvinces(String toolInput, AdmissionSlotsIr slots) {
        String toolProvince = parseProvince(toolInput);
        if (toolProvince != null && !toolProvince.isBlank()) {
            return List.of(toolProvince);
        }
        return resolveProvinces(toolInput, slots);
    }

    private List<String> resolveProvinces(String toolInput, AdmissionSlotsIr slots) {
        if (slots != null && !slots.provincesOrEmpty().isEmpty()) {
            return slots.provincesOrEmpty();
        }
        String toolProvince = parseProvince(toolInput);
        if (toolProvince != null && !toolProvince.isBlank()) {
            return List.of(toolProvince);
        }
        return List.of();
    }

    private String enrichToolInput(String toolInput, int score, String province, AdmissionSlotsIr slots) {
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            ObjectNode updated = node.deepCopy();
            updated.put("score", score);
            if (province != null && !province.isBlank()) {
                updated.put("province", province);
            }
            if (slots != null) {
                if (slots.subjectGroup() != null && !slots.subjectGroup().isBlank()) {
                    updated.put("subject_group", slots.subjectGroup());
                }
                if (slots.year() != null) {
                    updated.put("year", slots.year());
                }
                if (slots.admissionType() != null && !slots.admissionType().isBlank()) {
                    updated.put("admission_type", slots.admissionType());
                }
            }
            return objectMapper.writeValueAsString(updated);
        }
        catch (Exception ex) {
            return toolInput;
        }
    }

    private String enrichRankToolInput(String toolInput, int score, String province, AdmissionSlotsIr slots) {
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            ObjectNode updated = node.deepCopy();
            updated.put("score", score);
            if (province != null && !province.isBlank()) {
                updated.put("province", province);
            }
            String requestedSubjectGroup = resolveRequestedSubjectGroup(node, slots);
            updated.remove("subject_group");
            String rankSubjectGroup = RankSubjectGroupResolver.rankSubjectGroupForProvince(
                    province,
                    requestedSubjectGroup);
            if (rankSubjectGroup != null) {
                updated.put("subject_group", rankSubjectGroup);
            }
            if (slots != null) {
                if (slots.year() != null) {
                    updated.put("year", slots.year());
                }
                if (slots.admissionType() != null && !slots.admissionType().isBlank()) {
                    updated.put("admission_type", slots.admissionType());
                }
            }
            return objectMapper.writeValueAsString(updated);
        }
        catch (Exception ex) {
            return toolInput;
        }
    }

    private static String resolveRequestedSubjectGroup(JsonNode toolInput, AdmissionSlotsIr slots) {
        if (slots != null && slots.subjectGroup() != null && !slots.subjectGroup().isBlank()) {
            return slots.subjectGroup();
        }
        if (toolInput != null && toolInput.has("subject_group") && !toolInput.get("subject_group").isNull()) {
            String fromTool = toolInput.get("subject_group").asText("").strip();
            if (!fromTool.isBlank()) {
                return fromTool;
            }
        }
        return null;
    }

    private void captureTierTable(String userScoreToolInput, String tierLabel, ArrayNode majors) {
        mcpTableExtractor.extractMajorByScoreFromMajors(userScoreToolInput, majors, tierLabel)
                .ifPresent(McpTableContext::add);
    }

    private TierBuckets classifyMajors(JsonNode majorsNode, int baseScore) {
        ArrayNode reach = objectMapper.createArrayNode();
        ArrayNode steady = objectMapper.createArrayNode();
        ArrayNode safe = objectMapper.createArrayNode();
        double safeThreshold = baseScore - SCORE_TIER_DELTA;
        double reachUpper = baseScore + SCORE_TIER_DELTA;

        for (JsonNode major : majorsNode) {
            Double minScore = parseMajorMinScore(major);
            if (minScore == null) {
                continue;
            }
            if (minScore > baseScore && minScore <= reachUpper) {
                reach.add(major);
            }
            else if (minScore > safeThreshold && minScore <= baseScore) {
                steady.add(major);
            }
            else if (minScore <= safeThreshold) {
                safe.add(major);
            }
        }
        return new TierBuckets(reach, steady, safe);
    }

    private String buildTieredResponse(int baseScore, TierBuckets buckets) {
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("user_score", baseScore);
            response.put("tier_delta", SCORE_TIER_DELTA);
            ObjectNode tierCounts = objectMapper.createObjectNode();
            tierCounts.put(TIER_REACH, buckets.reach().size());
            tierCounts.put(TIER_STEADY, buckets.steady().size());
            tierCounts.put(TIER_SAFE, buckets.safe().size());
            response.set("tier_counts", tierCounts);
            ObjectNode majorsByTier = objectMapper.createObjectNode();
            majorsByTier.set(TIER_REACH, buckets.reach());
            majorsByTier.set(TIER_STEADY, buckets.steady());
            majorsByTier.set(TIER_SAFE, buckets.safe());
            response.set("majors_by_tier", majorsByTier);
            return objectMapper.writeValueAsString(response);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to build tiered getMajorByScore response", ex);
        }
    }

    private Double parseMajorMinScore(JsonNode major) {
        JsonNode minScoreNode = major.get("min_score");
        if (minScoreNode == null || minScoreNode.isNull()) {
            return null;
        }
        if (minScoreNode.isNumber()) {
            return minScoreNode.doubleValue();
        }
        String text = minScoreNode.asText("").strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isGetMajorByScore(String toolName) {
        return toolName != null && toolName.contains("getMajorByScore") && !toolName.contains("getMajorByRank");
    }

    private static boolean isGetMajorByRank(String toolName) {
        return toolName != null && toolName.contains("getMajorByRank");
    }

    private static boolean isGetRankByScore(String toolName) {
        return toolName != null && toolName.contains("getRankByScore");
    }

    private void captureRankResult(
            String toolInput,
            ToolContext toolContext,
            String responseData,
            String provinceOverride) {
        JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
        if (root == null || !root.has("ranks") || !root.get("ranks").isArray() || root.get("ranks").isEmpty()) {
            return;
        }
        Integer score = resolveRankScore(toolInput, toolContext);
        String province = provinceOverride != null && !provinceOverride.isBlank()
                ? provinceOverride
                : resolveRankProvince(toolInput, toolContext);
        if (McpRankContext.hasCapturedProvince(province)) {
            return;
        }
        String formatted = RankResponseFormatter.format(root, score, province);
        mcpTableExtractor.extractRankByScore(root, score, province).ifPresent(McpTableContext::add);
        McpRankContext.add(new McpRankContext.RankCapture(root, score, province, formatted));
    }

    private Integer resolveRankScore(String toolInput, ToolContext toolContext) {
        Optional<Integer> fromQuery = AdmissionQueryContext.current()
                .map(query -> query.slots().score());
        if (fromQuery.isPresent()) {
            return fromQuery.get();
        }
        Integer toolScore = parseScore(toolInput);
        Optional<Integer> parsedFromMessage = parseScoreFromUserMessage(toolContext);
        return parsedFromMessage.orElse(toolScore);
    }

    private String resolveRankProvince(String toolInput, ToolContext toolContext) {
        String toolProvince = parseProvince(toolInput);
        if (toolProvince != null && !toolProvince.isBlank()) {
            return toolProvince;
        }
        Optional<AdmissionQueryIr> queryOpt = AdmissionQueryContext.current();
        if (queryOpt.isPresent()) {
            List<String> provinces = queryOpt.get().slots().provincesOrEmpty();
            if (provinces.size() == 1) {
                return provinces.get(0);
            }
        }
        return parseProvinceFromUserMessage(toolContext).orElse(null);
    }

    private String parseProvince(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            JsonNode provinceNode = node.get("province");
            if (provinceNode == null || provinceNode.isNull()) {
                return null;
            }
            String province = provinceNode.asText("").strip();
            return province.isBlank() ? null : province;
        }
        catch (Exception ex) {
            return null;
        }
    }

    private static Optional<String> parseProvinceFromUserMessage(ToolContext toolContext) {
        if (toolContext == null) {
            return Optional.empty();
        }
        List<Message> history = toolContext.getToolCallHistory();
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message instanceof UserMessage userMessage) {
                AdmissionInputParser.ParsedAdmissionInput parsed =
                        AdmissionInputParser.parse(userMessage.getText());
                if (parsed.province() != null && !parsed.province().isBlank()) {
                    return Optional.of(parsed.province());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> resolveUserScore(ToolContext toolContext, Integer llmScore) {
        Optional<Integer> fromQuery = AdmissionQueryContext.current()
                .map(query -> query.slots().score());
        if (fromQuery.isPresent()) {
            return fromQuery;
        }
        Optional<Integer> parsedFromMessage = parseScoreFromUserMessage(toolContext);
        if (parsedFromMessage.isPresent()) {
            return parsedFromMessage;
        }
        return Optional.ofNullable(llmScore);
    }

    private static Optional<Integer> parseScoreFromUserMessage(ToolContext toolContext) {
        if (toolContext == null) {
            return Optional.empty();
        }
        List<Message> history = toolContext.getToolCallHistory();
        if (history == null || history.isEmpty()) {
            return Optional.empty();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message instanceof UserMessage userMessage) {
                return AdmissionInputParser.parseScore(userMessage.getText());
            }
        }
        return Optional.empty();
    }

    private Integer parseScore(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            JsonNode scoreNode = node.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                return null;
            }
            return scoreNode.intValue();
        }
        catch (Exception ex) {
            return null;
        }
    }

    private String withScore(String toolInput, int score) {
        try {
            JsonNode node = objectMapper.readTree(toolInput);
            ObjectNode updated = node.deepCopy();
            updated.put("score", score);
            return objectMapper.writeValueAsString(updated);
        }
        catch (Exception ex) {
            return toolInput;
        }
    }

    private static int clampScore(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private record TierBuckets(ArrayNode reach, ArrayNode steady, ArrayNode safe) {
    }
}
