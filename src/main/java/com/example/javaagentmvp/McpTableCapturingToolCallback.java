package com.example.javaagentmvp;

import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

/** Wraps MCP {@link ToolCallback} to capture structured tables for UI clients. */
public final class McpTableCapturingToolCallback implements ToolCallback {

    static final int SCORE_TIER_DELTA = 15;
    private static final int MIN_SCORE = 200;
    private static final int MAX_SCORE = 750;

    private static final String TIER_REACH = "冲";
    private static final String TIER_STEADY = "稳";
    private static final String TIER_SAFE = "保";

    private final ToolCallback delegate;
    private final McpTableExtractor mcpTableExtractor;
    private final ObjectMapper objectMapper;

    private McpTableCapturingToolCallback(
            ToolCallback delegate,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.mcpTableExtractor = mcpTableExtractor;
        this.objectMapper = objectMapper;
    }

    public static ToolCallback wrap(
            ToolCallback delegate,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        if (delegate instanceof McpTableCapturingToolCallback) {
            return delegate;
        }
        return new McpTableCapturingToolCallback(delegate, mcpTableExtractor, objectMapper);
    }

    public static List<ToolCallback> wrapAll(
            List<ToolCallback> callbacks,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        return callbacks.stream()
                .map(callback -> wrap(callback, mcpTableExtractor, objectMapper))
                .toList();
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
        if (!isGetMajorByScore(toolName)) {
            String responseData = delegate.call(toolInput, toolContext);
            mcpTableExtractor.extract(toolName, toolInput, responseData).ifPresent(McpTableContext::add);
            return responseData;
        }

        Integer baseScore = parseScore(toolInput);
        if (baseScore == null) {
            String responseData = delegate.call(toolInput, toolContext);
            mcpTableExtractor.extract(toolName, toolInput, responseData).ifPresent(McpTableContext::add);
            return responseData;
        }

        String queryInput = withScore(toolInput, clampScore(baseScore + SCORE_TIER_DELTA));
        String responseData = delegate.call(queryInput, toolContext);
        JsonNode root = mcpTableExtractor.parseMajorByScoreRoot(responseData).orElse(null);
        if (root == null) {
            return responseData;
        }

        JsonNode majorsNode = root.get("majors");
        if (majorsNode == null || !majorsNode.isArray()) {
            return responseData;
        }

        TierBuckets buckets = classifyMajors(majorsNode, baseScore);
        captureTierTable(toolInput, TIER_REACH, buckets.reach());
        captureTierTable(toolInput, TIER_STEADY, buckets.steady());
        captureTierTable(toolInput, TIER_SAFE, buckets.safe());
        return buildTieredResponse(baseScore, buckets);
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
        return toolName != null && toolName.contains("getMajorByScore");
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
