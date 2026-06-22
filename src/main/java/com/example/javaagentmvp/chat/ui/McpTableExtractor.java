package com.example.javaagentmvp.chat.ui;

import com.example.javaagentmvp.admissionworkflow.format.RankResponseFormatter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Autowired;
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
            new ChatTableColumn("plan_count", "计划数"),
            new ChatTableColumn("campus", "校区"),
            new ChatTableColumn("min_score", "最低分"),
            new ChatTableColumn("min_rank", "最低位次"),
            new ChatTableColumn("max_score", "最高分"),
            new ChatTableColumn("year", "年份"),
            new ChatTableColumn("subject_group", "科类"),
            new ChatTableColumn("admission_type", "批次"));

    private static final List<ChatTableColumn> RANK_BY_SCORE_COLUMNS = List.of(
            new ChatTableColumn("year_label", "年份"),
            new ChatTableColumn("subject_group", "科类"),
            new ChatTableColumn("rank_range", "位次区间"),
            new ChatTableColumn("segment_count", "同分数段人数"),
            new ChatTableColumn("source_label", "数据来源"));

    private final ObjectMapper objectMapper;
    private final ChatTableEnrichmentService tableEnrichmentService;

    /** Test/manual construction without Spring context. */
    public McpTableExtractor(ObjectMapper objectMapper) {
        this(objectMapper, ChatTableEnrichmentService.noop());
    }

    @Autowired
    public McpTableExtractor(ObjectMapper objectMapper, ChatTableEnrichmentService tableEnrichmentService) {
        this.objectMapper = objectMapper;
        this.tableEnrichmentService = tableEnrichmentService == null
                ? ChatTableEnrichmentService.noop()
                : tableEnrichmentService;
    }

    public Optional<ChatTable> extract(String toolName, String toolInput, String responseData) {
        return extract(toolName, toolInput, responseData, null);
    }

    public Optional<ChatTable> extract(String toolName, String toolInput, String responseData, String tierLabel) {
        if (responseData == null || responseData.isBlank()) {
            return Optional.empty();
        }
        String payload = unwrapToolPayload(responseData);
        if (matchesTool(toolName, "getMajorByScore")
                || matchesTool(toolName, "getMajorByRank")
                || hasMajorByScorePayload(payload)) {
            return extractMajorByScore(toolInput, payload, tierLabel);
        }
        return Optional.empty();
    }

    /** Builds a tier table from a pre-classified majors array (title uses {@code toolInput} score). */
    public Optional<ChatTable> extractMajorByScoreFromMajors(
            String toolInput,
            JsonNode majorsNode,
            String tierLabel) {
        if (majorsNode == null || !majorsNode.isArray()) {
            return Optional.empty();
        }
        if (majorsNode.isEmpty() && (tierLabel == null || tierLabel.isBlank())) {
            return Optional.empty();
        }
        return buildMajorByScoreTable(toolInput, majorsNode, tierLabel);
    }

    public Optional<ChatTable> extractRankByScore(JsonNode root, Integer score, String province) {
        if (root == null || !root.has("ranks") || !root.get("ranks").isArray()) {
            return Optional.empty();
        }
        JsonNode ranks = root.get("ranks");
        if (ranks.isEmpty()) {
            return Optional.empty();
        }
        int queryScore = score != null
                ? score
                : ranks.get(0).path("score").asInt(0);
        String tableProvince = resolveTableProvince(province, ranks);
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode rank : RankResponseFormatter.sortedRankRows(ranks)) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("year_label", RankResponseFormatter.yearLabelPlain(rank, queryScore));
            row.put("subject_group", formatCell(rank.get("subject_group")));
            row.put("rank_range", RankResponseFormatter.rankRangePlain(rank));
            row.put("segment_count", RankResponseFormatter.segmentCountPlain(rank));
            row.put("source_label", RankResponseFormatter.sourceLabelPlain(rank));
            String sourceUrl = rank.path("source_url").asText("").strip();
            if (!sourceUrl.isBlank()) {
                row.put("source_url", sourceUrl);
            }
            if (!tableProvince.isBlank()) {
                row.put("province", tableProvince);
            }
            rows.add(row);
        }
        String title = tableProvince.isBlank() ? "" : tableProvince;
        return Optional.of(new ChatTable(title, RANK_BY_SCORE_COLUMNS, rows, tableProvince.isBlank() ? null : tableProvince));
    }

    private static String resolveTableProvince(String province, JsonNode ranks) {
        if (province != null && !province.isBlank()) {
            return province.strip();
        }
        if (ranks != null && ranks.isArray() && !ranks.isEmpty()) {
            String fromRow = ranks.get(0).path("province").asText("").strip();
            if (!fromRow.isBlank()) {
                return fromRow;
            }
        }
        return "";
    }

    public String unwrapToolPayload(String responseData) {
        return unwrapToolPayloadInternal(responseData);
    }

    public Optional<JsonNode> parseMajorByScoreRoot(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(unwrapToolPayload(responseData)));
        }
        catch (Exception ex) {
            return Optional.empty();
        }
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
    private String unwrapToolPayloadInternal(String responseData) {
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

    private Optional<ChatTable> extractMajorByScore(String toolInput, String responseData, String tierLabel) {
        JsonNode root;
        try {
            root = objectMapper.readTree(responseData);
        }
        catch (Exception ex) {
            return Optional.empty();
        }
        JsonNode majorsNode = root.get("majors");
        if (majorsNode == null || !majorsNode.isArray()) {
            return Optional.empty();
        }
        if (majorsNode.isEmpty() && tierLabel == null) {
            return Optional.empty();
        }
        return buildMajorByScoreTable(toolInput, majorsNode, tierLabel);
    }

    private Optional<ChatTable> buildMajorByScoreTable(String toolInput, JsonNode majorsNode, String tierLabel) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode major : majorsNode) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("university_code", formatCell(major.get("university_code")));
            for (ChatTableColumn column : MAJOR_BY_SCORE_COLUMNS) {
                row.put(column.key(), formatCell(major.get(column.key())));
            }
            rows.add(row);
        }
        ChatTable table = new ChatTable(buildMajorByScoreTitle(toolInput, tierLabel), MAJOR_BY_SCORE_COLUMNS, rows);
        return Optional.of(tableEnrichmentService.enrichTable(table));
    }

    public ArrayNode copyMajorsArray(JsonNode majorsNode) {
        ArrayNode copy = objectMapper.createArrayNode();
        if (majorsNode != null && majorsNode.isArray()) {
            majorsNode.forEach(copy::add);
        }
        return copy;
    }

    private String buildMajorByScoreTitle(String toolInput, String tierLabel) {
        JsonNode args = parseJson(toolInput);
        List<String> parts = new ArrayList<>();
        if (args.has("rank")) {
            parts.add("排名" + stripTrailingZeros(args.get("rank").asText()) + "名");
        }
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
        String detail = parts.isEmpty() ? "可报专业" : String.join(" · ", parts);
        if (tierLabel != null && !tierLabel.isBlank()) {
            return tierLabel + "（" + detail + "）";
        }
        if (parts.isEmpty()) {
            return "可报专业";
        }
        return "可报专业（" + detail + "）";
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
