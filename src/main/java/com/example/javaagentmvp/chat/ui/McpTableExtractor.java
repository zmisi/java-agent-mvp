package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class McpTableExtractor {

    private static final List<ChatTableColumn> MAJOR_BY_SCORE_COLUMNS = List.of(
            new ChatTableColumn("university_name", "院校"),
            new ChatTableColumn("major_name", "专业"),
            new ChatTableColumn("campus", "校区"),
            new ChatTableColumn("min_score", "最低分"),
            new ChatTableColumn("min_rank", "最低位次"),
            new ChatTableColumn("max_score", "最高分"),
            new ChatTableColumn("year", "年份"),
            new ChatTableColumn("subject_group", "科类"),
            new ChatTableColumn("admission_type", "批次"));

    private final ObjectMapper objectMapper;

    public McpTableExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<ChatTable> extract(String toolName, String toolInput, String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return Optional.empty();
        }
        String payload = unwrapToolPayload(responseData);
        if (matchesTool(toolName, "getMajorByScore") || hasMajorByScorePayload(payload)) {
            return extractMajorByScore(toolInput, payload);
        }
        return Optional.empty();
    }

    /** Spring AI MCP prefixes tool names, e.g. {@code opstream_agent_admission_score_getMajorByScore}. */
    private static boolean matchesTool(String toolName, String baseName) {
        if (toolName == null || baseName == null || baseName.isBlank()) {
            return false;
        }
        if (toolName.equals(baseName)) {
            return true;
        }
        return toolName.endsWith("_" + baseName)
                || toolName.endsWith("-" + baseName)
                || toolName.endsWith("." + baseName)
                || toolName.contains(baseName);
    }

    private boolean hasMajorByScorePayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode majorsNode = root.get("majors");
            return majorsNode != null && majorsNode.isArray() && !majorsNode.isEmpty();
        }
        catch (Exception ex) {
            return false;
        }
    }

    /**
     * Tool callbacks may return MCP content blocks, e.g. {@code [{"text":"{\"count\":1,...}"}]}.
     */
    private String unwrapToolPayload(String responseData) {
        String trimmed = responseData.strip();
        if (!trimmed.startsWith("[")) {
            return trimmed;
        }
        try {
            JsonNode root = objectMapper.readTree(trimmed);
            if (!root.isArray()) {
                return trimmed;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode item : root) {
                JsonNode textNode = item.get("text");
                if (textNode != null && textNode.isTextual()) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(textNode.asText());
                }
            }
            return text.isEmpty() ? trimmed : text.toString().strip();
        }
        catch (Exception ex) {
            return trimmed;
        }
    }

    public List<ChatTable> extractFromToolResponses(List<ToolResponsePayload> toolResponses) {
        if (toolResponses == null || toolResponses.isEmpty()) {
            return List.of();
        }
        List<ChatTable> tables = new ArrayList<>();
        for (ToolResponsePayload response : toolResponses) {
            extract(response.name(), response.arguments(), response.responseData())
                    .ifPresent(tables::add);
        }
        return tables;
    }

    private Optional<ChatTable> extractMajorByScore(String toolInput, String responseData) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseData);
        }
        catch (Exception ex) {
            return Optional.empty();
        }
        JsonNode majorsNode = root.get("majors");
        if (majorsNode == null || !majorsNode.isArray() || majorsNode.isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode major : majorsNode) {
            Map<String, String> row = new LinkedHashMap<>();
            for (ChatTableColumn column : MAJOR_BY_SCORE_COLUMNS) {
                row.put(column.key(), formatCell(major.get(column.key())));
            }
            rows.add(row);
        }

        return Optional.of(new ChatTable(buildMajorByScoreTitle(toolInput), MAJOR_BY_SCORE_COLUMNS, rows));
    }

    private String buildMajorByScoreTitle(String toolInput) {
        JsonNode args = parseJson(toolInput);
        List<String> parts = new ArrayList<>();
        if (args.has("score")) {
            parts.add(stripTrailingZeros(args.get("score").asText()) + "分");
        }
        if (args.has("province")) {
            parts.add(args.get("province").asText(""));
        }
        if (args.has("year")) {
            parts.add(args.get("year").asText(""));
        }
        if (args.has("subject_group")) {
            parts.add(args.get("subject_group").asText(""));
        }
        if (args.has("admission_type")) {
            parts.add(args.get("admission_type").asText(""));
        }
        if (parts.isEmpty()) {
            return "可报专业";
        }
        return "可报专业（" + String.join(" · ", parts) + "）";
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(raw);
        }
        catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private static String formatCell(JsonNode value) {
        if (value == null || value.isNull()) {
            return "-";
        }
        String text = value.isNumber() ? value.asText() : value.asText("").strip();
        if (text.isEmpty()) {
            return "-";
        }
        return stripTrailingZeros(text);
    }

    private static String stripTrailingZeros(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }

    public record ToolResponsePayload(String name, String arguments, String responseData) {
    }
}
