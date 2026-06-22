package com.example.javaagentmvp.admissionworkflow.tool;

import com.example.javaagentmvp.observability.AgentMetrics;
import com.example.javaagentmvp.observability.TraceResponseFilter;
import com.example.javaagentmvp.admissionworkflow.DefaultAdmissionYear;
import com.example.javaagentmvp.admissionworkflow.format.RankSubjectGroupResolver;
import com.example.javaagentmvp.admissionworkflow.format.ScoreToRankResolver;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.example.javaagentmvp.dbagent.DbAgentTargetRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
@Component
public class AdmissionScoreToolClient {

    private static final Logger log = LoggerFactory.getLogger(AdmissionScoreToolClient.class);
    private static final int MAX_LOG_CHARS = 2_000;
    /** MCP returns majors with min_score <= query score; query user score + delta to include 冲档. */
    public static final int SCORE_TIER_DELTA = 15;
    private static final int MIN_SCORE = 200;
    private static final int MAX_SCORE = 750;

    private final DbAgentTargetRegistry dbAgentTargetRegistry;
    private final McpTableExtractor mcpTableExtractor;
    private final ObjectMapper objectMapper;
    private final AgentMetrics agentMetrics;
    private final ObservationRegistry observationRegistry;

    public AdmissionScoreToolClient(
            DbAgentTargetRegistry dbAgentTargetRegistry,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper,
            AgentMetrics agentMetrics,
            ObservationRegistry observationRegistry) {
        this.dbAgentTargetRegistry = dbAgentTargetRegistry;
        this.mcpTableExtractor = mcpTableExtractor;
        this.objectMapper = objectMapper;
        this.agentMetrics = agentMetrics;
        this.observationRegistry = observationRegistry;
    }

    public JsonNode getRankByScore(
            String runId,
            int score,
            String province,
            String subjectGroup,
            Integer year) {
        ToolCallback callback = resolveRankCallback();
        String toolName = callback.getToolDefinition().name();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("score", score);
        if (province != null && !province.isBlank()) {
            args.put("province", province);
        }
        String rankSubjectGroup = RankSubjectGroupResolver.rankSubjectGroupForProvince(province, subjectGroup);
        if (rankSubjectGroup != null) {
            args.put("subject_group", rankSubjectGroup);
        }
        args.put("year", DefaultAdmissionYear.resolve(year));

        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize getRankByScore args", ex);
        }

        log.info("[WORKFLOW MCP runId={}] >>> {} score={} args={}", runId, toolName, score, argsJson);

        long startedAt = System.nanoTime();
        String response;
        try {
            response = TraceResponseFilter.observe(
                    observationRegistry,
                    "agent.tool.getRankByScore",
                    "getRankByScore",
                    () -> callback.call(argsJson));
        }
        catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            agentMetrics.recordToolCall("getRankByScore", elapsedMs);
            log.error("[WORKFLOW MCP runId={}] <<< {} error ({} ms): {}",
                    runId, toolName, elapsedMs, ex.getMessage(), ex);
            throw ex;
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        agentMetrics.recordToolCall("getRankByScore", elapsedMs);
        JsonNode parsed = mcpTableExtractor.parseMajorByScoreRoot(response).orElse(null);
        if (parsed == null) {
            log.error("[WORKFLOW MCP runId={}] <<< {} invalid JSON ({} ms): {}",
                    runId, toolName, elapsedMs, truncate(response));
            throw new IllegalStateException("Failed to parse getRankByScore response");
        }

        int count = parsed.path("count").asInt(parsed.path("ranks").size());
        log.info("[WORKFLOW MCP runId={}] <<< {} ({} ms) score={} count={} data={}",
                runId, toolName, elapsedMs, score, count, truncate(mcpTableExtractor.unwrapToolPayload(response)));
        return parsed;
    }

    /**
     * Resolves score to provincial rank via {@code getRankByScore}, then queries majors with
     * {@code getMajorByRank} (more accurate than {@code getMajorByScore}).
     */
    public JsonNode getMajorsForScore(
            String runId,
            int userScore,
            String province,
            String subjectGroup,
            Integer year,
            String admissionType) {
        int effectiveYear = DefaultAdmissionYear.resolve(year);
        JsonNode rankResult = getRankByScore(runId, userScore, province, subjectGroup, effectiveYear);
        int userRank = ScoreToRankResolver.resolveRank(rankResult, effectiveYear, subjectGroup)
                .orElseThrow(() -> new IllegalStateException(
                        "无法将 " + userScore + " 分转换为位次，请补充省份、科类或年份"));
        JsonNode majorResult = getMajorByRank(
                runId, userRank, province, subjectGroup, effectiveYear, admissionType);
        if (majorResult instanceof ObjectNode objectNode) {
            objectNode.put("user_score", userScore);
            objectNode.put("resolved_rank", userRank);
        }
        log.info("[WORKFLOW MCP runId={}] score={} province={} resolvedRank={} majorCount={}",
                runId,
                userScore,
                province,
                userRank,
                majorResult.path("count").asInt(majorResult.path("majors").size()));
        return majorResult;
    }

    public JsonNode getMajorByScore(
            String runId,
            int userScore,
            String province,
            String subjectGroup,
            Integer year,
            String admissionType) {
        int queryScore = mcpQueryScore(userScore);
        ToolCallback callback = resolveCallback();
        String toolName = callback.getToolDefinition().name();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("score", queryScore);
        args.put("province", province);
        if (subjectGroup != null && !subjectGroup.isBlank()) {
            args.put("subject_group", subjectGroup);
        }
        args.put("year", DefaultAdmissionYear.resolve(year));
        if (admissionType != null && !admissionType.isBlank()) {
            args.put("admission_type", admissionType);
        }

        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize getMajorByScore args", ex);
        }

        log.info("[WORKFLOW MCP runId={}] >>> {} userScore={} queryScore={} args={}",
                runId, toolName, userScore, queryScore, argsJson);

        long startedAt = System.nanoTime();
        String response;
        try {
            response = TraceResponseFilter.observe(
                    observationRegistry,
                    "agent.tool.getMajorByScore",
                    "getMajorByScore",
                    () -> callback.call(argsJson));
        }
        catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            agentMetrics.recordToolCall("getMajorByScore", elapsedMs);
            log.error("[WORKFLOW MCP runId={}] <<< {} error ({} ms): {}",
                    runId, toolName, elapsedMs, ex.getMessage(), ex);
            throw ex;
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        agentMetrics.recordToolCall("getMajorByScore", elapsedMs);
        JsonNode parsed = mcpTableExtractor.parseMajorByScoreRoot(response).orElse(null);
        if (parsed == null) {
            log.error("[WORKFLOW MCP runId={}] <<< {} invalid JSON ({} ms): {}",
                    runId, toolName, elapsedMs, truncate(response));
            throw new IllegalStateException("Failed to parse getMajorByScore response");
        }

        int count = parsed.path("count").asInt(parsed.path("majors").size());
        if (parsed instanceof ObjectNode objectNode) {
            objectNode.put("user_score", userScore);
            objectNode.put("tier_delta", SCORE_TIER_DELTA);
            objectNode.put("mcp_query_score", queryScore);
        }
        log.info("[WORKFLOW MCP runId={}] <<< {} ({} ms) userScore={} queryScore={} count={} data={}",
                runId, toolName, elapsedMs, userScore, queryScore, count, truncate(mcpTableExtractor.unwrapToolPayload(response)));
        return parsed;
    }

    public JsonNode getMajorByRank(
            String runId,
            int userRank,
            String province,
            String subjectGroup,
            Integer year,
            String admissionType) {
        ToolCallback callback = resolveMajorByRankCallback();
        String toolName = callback.getToolDefinition().name();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("rank", userRank);
        args.put("province", province);
        if (subjectGroup != null && !subjectGroup.isBlank()) {
            args.put("subject_group", subjectGroup);
        }
        args.put("year", DefaultAdmissionYear.resolve(year));
        if (admissionType != null && !admissionType.isBlank()) {
            args.put("admission_type", admissionType);
        }

        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize getMajorByRank args", ex);
        }

        log.info("[WORKFLOW MCP runId={}] >>> {} userRank={} args={}",
                runId, toolName, userRank, argsJson);

        long startedAt = System.nanoTime();
        String response;
        try {
            response = TraceResponseFilter.observe(
                    observationRegistry,
                    "agent.tool.getMajorByRank",
                    "getMajorByRank",
                    () -> callback.call(argsJson));
        }
        catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            agentMetrics.recordToolCall("getMajorByRank", elapsedMs);
            log.error("[WORKFLOW MCP runId={}] <<< {} error ({} ms): {}",
                    runId, toolName, elapsedMs, ex.getMessage(), ex);
            throw ex;
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        agentMetrics.recordToolCall("getMajorByRank", elapsedMs);
        JsonNode parsed = mcpTableExtractor.parseMajorByScoreRoot(response).orElse(null);
        if (parsed == null) {
            log.error("[WORKFLOW MCP runId={}] <<< {} invalid JSON ({} ms): {}",
                    runId, toolName, elapsedMs, truncate(response));
            throw new IllegalStateException("Failed to parse getMajorByRank response");
        }

        int count = parsed.path("count").asInt(parsed.path("majors").size());
        if (parsed instanceof ObjectNode objectNode) {
            objectNode.put("user_rank", userRank);
        }
        log.info("[WORKFLOW MCP runId={}] <<< {} ({} ms) userRank={} count={} data={}",
                runId, toolName, elapsedMs, userRank, count, truncate(mcpTableExtractor.unwrapToolPayload(response)));
        return parsed;
    }

    /** MCP query score: user score + 15 (冲档上限), clamped to valid range. */
    public static int mcpQueryScore(int userScore) {
        return clampScore(userScore + SCORE_TIER_DELTA);
    }

    static int clampScore(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.length() <= MAX_LOG_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_CHARS) + "... (" + normalized.length() + " chars total)";
    }

    private ToolCallback resolveRankCallback() {
        List<ToolCallback> callbacks =
                SyncMcpToolCallbackProvider.syncToolCallbacks(dbAgentTargetRegistry.chatMcpClients());
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().contains("getRankByScore"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("getRankByScore MCP tool not found"));
    }

    private ToolCallback resolveCallback() {
        List<ToolCallback> callbacks =
                SyncMcpToolCallbackProvider.syncToolCallbacks(dbAgentTargetRegistry.chatMcpClients());
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().contains("getMajorByScore")
                        && !callback.getToolDefinition().name().contains("getMajorByRank"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("getMajorByScore MCP tool not found"));
    }

    private ToolCallback resolveMajorByRankCallback() {
        List<ToolCallback> callbacks =
                SyncMcpToolCallbackProvider.syncToolCallbacks(dbAgentTargetRegistry.chatMcpClients());
        return callbacks.stream()
                .filter(callback -> callback.getToolDefinition().name().contains("getMajorByRank"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("getMajorByRank MCP tool not found"));
    }
}
