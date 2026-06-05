package com.example.javaagentmvp.chat.ui;

import com.example.javaagentmvp.chat.MessagePayloadCodec;
import com.example.javaagentmvp.chat.persistence.model.ChatMemoryMessageRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public final class TranscriptBuilder {

    private final McpTableExtractor mcpTableExtractor;
    private final ObjectMapper objectMapper;

    public TranscriptBuilder(McpTableExtractor mcpTableExtractor, ObjectMapper objectMapper) {
        this.mcpTableExtractor = mcpTableExtractor;
        this.objectMapper = objectMapper;
    }

    public List<TranscriptRow> build(List<ChatMemoryMessageRow> rows) {
        List<ParsedRow> parsedRows = new ArrayList<>();
        for (ChatMemoryMessageRow row : rows) {
            parsedRows.add(parseRow(row));
        }

        List<TranscriptRow> transcript = new ArrayList<>();
        List<McpTableExtractor.ToolResponsePayload> pendingTools = new ArrayList<>();
        List<McpTableExtractor.ToolResponsePayload> pendingToolRequests = new ArrayList<>();

        for (ParsedRow row : parsedRows) {
            if ("tool".equals(row.role())) {
                pendingTools.addAll(mergeToolResponses(row.toolResponses(), pendingToolRequests));
                pendingToolRequests.clear();
                continue;
            }
            if ("assistant".equals(row.role()) && row.hasToolCalls()) {
                pendingToolRequests = row.pendingToolRequests();
                continue;
            }
            if ("user".equals(row.role())) {
                pendingTools.clear();
                pendingToolRequests.clear();
                transcript.add(new TranscriptRow(row.id(), row.createdAt(), row.role(), row.text(), List.of()));
                continue;
            }
            if ("assistant".equals(row.role())) {
                List<ChatTable> tables = row.uiTables();
                if (tables.isEmpty()) {
                    tables = mcpTableExtractor.extractFromToolResponses(pendingTools);
                }
                pendingTools.clear();
                transcript.add(new TranscriptRow(
                        row.id(),
                        row.createdAt(),
                        row.role(),
                        row.text(),
                        ChatTableGrouper.enrichTables(tables)));
            }
        }
        return transcript;
    }

    public static boolean hasToolCalls(JsonNode node) {
        if (!"assistant".equalsIgnoreCase(node.path("kind").asText(""))) {
            return false;
        }
        JsonNode toolCallsNode = node.get("toolCalls");
        return toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty();
    }

    private ParsedRow parseRow(ChatMemoryMessageRow row) {
        try {
            JsonNode node = objectMapper.readTree(row.getPayloadJson());
            String role = MessagePayloadCodec.displayRole(node);
            String text = MessagePayloadCodec.toDisplayText(node);
            boolean hasToolCalls = hasToolCalls(node);
            List<ChatTable> uiTables = UiTableCodec.readUiTables(node, objectMapper);
            List<McpTableExtractor.ToolResponsePayload> toolResponses = extractToolResponses(node);
            List<McpTableExtractor.ToolResponsePayload> pendingToolRequests = extractToolCallRequests(node);
            return new ParsedRow(
                    row.getId(),
                    row.getCreatedAt(),
                    role,
                    text,
                    hasToolCalls,
                    uiTables,
                    toolResponses,
                    pendingToolRequests);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to parse chat payload id=" + row.getId(), ex);
        }
    }

    private List<McpTableExtractor.ToolResponsePayload> extractToolResponses(JsonNode node) {
        if (!"tool".equalsIgnoreCase(node.path("kind").asText(""))) {
            return List.of();
        }
        JsonNode responsesNode = node.get("responses");
        if (responsesNode == null || !responsesNode.isArray()) {
            return List.of();
        }
        List<McpTableExtractor.ToolResponsePayload> responses = new ArrayList<>();
        for (JsonNode response : responsesNode) {
            responses.add(new McpTableExtractor.ToolResponsePayload(
                    response.path("name").asText(""),
                    "",
                    responseDataAsString(response)));
        }
        return responses;
    }

    private String responseDataAsString(JsonNode response) {
        JsonNode data = response.get("responseData");
        if (data == null || data.isNull()) {
            return "";
        }
        if (data.isTextual()) {
            return data.asText("");
        }
        try {
            return objectMapper.writeValueAsString(data);
        }
        catch (JsonProcessingException ex) {
            return data.toString();
        }
    }

    private static List<McpTableExtractor.ToolResponsePayload> extractToolCallRequests(JsonNode node) {
        if (!"assistant".equalsIgnoreCase(node.path("kind").asText(""))) {
            return List.of();
        }
        JsonNode toolCallsNode = node.get("toolCalls");
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return List.of();
        }
        List<McpTableExtractor.ToolResponsePayload> requests = new ArrayList<>();
        for (JsonNode toolCall : toolCallsNode) {
            requests.add(new McpTableExtractor.ToolResponsePayload(
                    toolCall.path("name").asText(""),
                    toolCall.path("arguments").asText(""),
                    ""));
        }
        return requests;
    }

    private static List<McpTableExtractor.ToolResponsePayload> mergeToolResponses(
            List<McpTableExtractor.ToolResponsePayload> toolResponses,
            List<McpTableExtractor.ToolResponsePayload> pendingToolRequests) {
        if (toolResponses.isEmpty()) {
            return List.of();
        }
        List<McpTableExtractor.ToolResponsePayload> merged = new ArrayList<>();
        for (McpTableExtractor.ToolResponsePayload response : toolResponses) {
            String arguments = pendingToolRequests.stream()
                    .filter(request -> toolNamesMatch(request.name(), response.name()))
                    .map(McpTableExtractor.ToolResponsePayload::arguments)
                    .findFirst()
                    .orElse("");
            merged.add(new McpTableExtractor.ToolResponsePayload(
                    response.name(),
                    arguments,
                    response.responseData()));
        }
        return merged;
    }

    private static boolean toolNamesMatch(String requestName, String responseName) {
        if (requestName == null || responseName == null) {
            return false;
        }
        if (requestName.equals(responseName)) {
            return true;
        }
        return requestName.contains("getMajorByScore") && responseName.contains("getMajorByScore");
    }

    private record ParsedRow(
            long id,
            String createdAt,
            String role,
            String text,
            boolean hasToolCalls,
            List<ChatTable> uiTables,
            List<McpTableExtractor.ToolResponsePayload> toolResponses,
            List<McpTableExtractor.ToolResponsePayload> pendingToolRequests) {
    }

    public record TranscriptRow(long id, String createdAt, String role, String text, List<ChatTable> tables) {
    }
}
