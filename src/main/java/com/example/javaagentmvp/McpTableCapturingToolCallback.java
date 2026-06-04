package com.example.javaagentmvp;

import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

        String steadyResponse = delegate.call(toolInput, toolContext);
        captureTierTable(toolName, toolInput, TIER_REACH, baseScore + SCORE_TIER_DELTA, null, toolContext);
        captureTierTable(toolName, toolInput, TIER_STEADY, baseScore, steadyResponse, toolContext);
        captureTierTable(toolName, toolInput, TIER_SAFE, baseScore - SCORE_TIER_DELTA, null, toolContext);
        return steadyResponse;
    }

    private void captureTierTable(
            String toolName,
            String toolInput,
            String tierLabel,
            int queryScore,
            String cachedResponse,
            ToolContext toolContext) {
        String tierInput = withScore(toolInput, clampScore(queryScore));
        String responseData = cachedResponse != null ? cachedResponse : delegate.call(tierInput, toolContext);
        mcpTableExtractor.extract(toolName, tierInput, responseData, tierLabel).ifPresent(McpTableContext::add);
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
}
