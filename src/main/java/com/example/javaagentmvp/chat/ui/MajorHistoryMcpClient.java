package com.example.javaagentmvp.chat.ui;

import com.example.javaagentmvp.admissionworkflow.DefaultAdmissionYear;
import com.example.javaagentmvp.dbagent.DbAgentTargetRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MajorHistoryMcpClient {

    private static final Logger log = LoggerFactory.getLogger(MajorHistoryMcpClient.class);

    private final ToolCallback historyToolCallback;
    private final ObjectMapper objectMapper;

    @Autowired
    public MajorHistoryMcpClient(
            DbAgentTargetRegistry dbAgentTargetRegistry,
            ObjectMapper objectMapper) {
        this.historyToolCallback = resolveHistoryCallback(dbAgentTargetRegistry);
        this.objectMapper = objectMapper;
    }

    static MajorHistoryMcpClient forTest(ToolCallback historyToolCallback, ObjectMapper objectMapper) {
        return new MajorHistoryMcpClient(historyToolCallback, objectMapper);
    }

    private MajorHistoryMcpClient(ToolCallback historyToolCallback, ObjectMapper objectMapper) {
        this.historyToolCallback = historyToolCallback;
        this.objectMapper = objectMapper;
    }

    static MajorHistoryMcpClient noop(ObjectMapper objectMapper) {
        return forTest(null, objectMapper);
    }

    public Map<MajorHistoryTableExpander.MajorHistoryKey, Map<Integer, Map<String, String>>> fetchHistory(
            String province,
            int baseYear,
            List<MajorHistoryRequest> requests) {
        if (historyToolCallback == null || requests == null || requests.isEmpty()) {
            return Map.of();
        }
        if (province == null || province.isBlank() || !MajorHistoryEnrichmentService.isLikelyProvince(province)) {
            return Map.of();
        }
        if (baseYear != DefaultAdmissionYear.VALUE) {
            return Map.of();
        }

        ObjectNode args = objectMapper.createObjectNode();
        args.put("province", province.strip());
        args.put("base_year", baseYear);
        ArrayNode majors = objectMapper.createArrayNode();
        for (MajorHistoryRequest request : dedupeRequests(requests)) {
            ObjectNode major = objectMapper.createObjectNode();
            major.put("university_code", request.universityCode());
            major.put("major_name", request.majorName());
            putIfPresent(major, "campus", request.campus());
            putIfPresent(major, "subject_group", request.subjectGroup());
            putIfPresent(major, "admission_type", request.admissionType());
            majors.add(major);
        }
        args.set("majors", majors);

        String argsJson;
        try {
            argsJson = objectMapper.writeValueAsString(args);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize getMajorHistory args", ex);
        }

        String response;
        try {
            response = historyToolCallback.call(argsJson);
        }
        catch (RuntimeException ex) {
            log.warn("getMajorHistory failed: {}", ex.getMessage());
            return Map.of();
        }

        JsonNode root = parseToolResponse(response);
        if (root == null || !root.has("rows") || !root.get("rows").isArray()) {
            return Map.of();
        }

        Map<MajorHistoryTableExpander.MajorHistoryKey, Map<Integer, Map<String, String>>> history = new LinkedHashMap<>();
        Map<String, MajorHistoryRequest> requestByMajor = indexRequestsByMajor(dedupeRequests(requests));
        for (JsonNode row : root.get("rows")) {
            String universityCode = text(row.get("lookup_university_code"));
            if (universityCode.isBlank()) {
                universityCode = text(row.get("university_code"));
            }
            String majorName = text(row.get("lookup_major_name"));
            if (majorName.isBlank()) {
                majorName = text(row.get("major_name"));
            }
            int year = row.path("year").asInt(0);
            if (universityCode.isBlank() || majorName.isBlank() || year <= 0) {
                continue;
            }
            MajorHistoryRequest matched = requestByMajor.get(universityCode + "\u0000" + majorName);
            if (matched == null) {
                continue;
            }
            MajorHistoryTableExpander.MajorHistoryKey key = keyFromRequest(matched);
            history.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .putIfAbsent(year, toHistoryFields(row));
        }
        log.info("getMajorHistory province={} majors={} hits={}", province, majors.size(), history.size());
        return history;
    }

    private static List<MajorHistoryRequest> dedupeRequests(List<MajorHistoryRequest> requests) {
        Map<String, MajorHistoryRequest> deduped = new LinkedHashMap<>();
        for (MajorHistoryRequest request : requests) {
            String key = request.universityCode()
                    + "\u0000"
                    + request.majorName()
                    + "\u0000"
                    + nullToEmpty(request.campus())
                    + "\u0000"
                    + nullToEmpty(request.subjectGroup())
                    + "\u0000"
                    + nullToEmpty(request.admissionType());
            deduped.putIfAbsent(key, request);
        }
        return new ArrayList<>(deduped.values());
    }

    private static Map<String, MajorHistoryRequest> indexRequestsByMajor(List<MajorHistoryRequest> requests) {
        Map<String, MajorHistoryRequest> index = new LinkedHashMap<>();
        for (MajorHistoryRequest request : requests) {
            index.putIfAbsent(request.universityCode() + "\u0000" + nullToEmpty(request.majorName()), request);
        }
        return index;
    }

    private static MajorHistoryTableExpander.MajorHistoryKey keyFromRequest(MajorHistoryRequest request) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("major_name", request.majorName());
        row.put("campus", request.campus());
        row.put("subject_group", request.subjectGroup());
        row.put("admission_type", request.admissionType());
        return MajorHistoryTableExpander.MajorHistoryKey.from(request.universityCode(), row);
    }

    private static Map<String, String> toHistoryFields(JsonNode row) {
        Map<String, String> fields = new LinkedHashMap<>();
        putFormatted(fields, "plan_count", row.get("plan_count"));
        putFormatted(fields, "campus", row.get("campus"));
        putFormatted(fields, "min_score", row.get("min_score"));
        putFormatted(fields, "min_rank", row.get("min_rank"));
        putFormatted(fields, "max_score", row.get("max_score"));
        putFormatted(fields, "year", row.get("year"));
        putFormatted(fields, "subject_group", row.get("subject_group"));
        putFormatted(fields, "admission_type", row.get("admission_type"));
        return fields;
    }

    private static void putFormatted(Map<String, String> target, String key, JsonNode value) {
        if (value == null || value.isNull()) {
            target.put(key, "-");
            return;
        }
        String text = value.isNumber() ? value.asText() : value.asText("").strip();
        target.put(key, text.isEmpty() ? "-" : stripTrailingZeros(text));
    }

    private static void putIfPresent(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank() && !"-".equals(value)) {
            node.put(key, value);
        }
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText("").strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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

    private JsonNode parseToolResponse(String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(unwrapToolPayload(responseData));
        }
        catch (Exception ex) {
            return null;
        }
    }

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

    private static ToolCallback resolveHistoryCallback(DbAgentTargetRegistry dbAgentTargetRegistry) {
        try {
            List<ToolCallback> callbacks =
                    SyncMcpToolCallbackProvider.syncToolCallbacks(dbAgentTargetRegistry.chatMcpClients());
            return callbacks.stream()
                    .filter(callback -> callback.getToolDefinition().name().contains("getMajorHistory"))
                    .findFirst()
                    .orElse(null);
        }
        catch (RuntimeException ex) {
            log.warn("getMajorHistory MCP tool unavailable: {}", ex.getMessage());
            return null;
        }
    }

    public record MajorHistoryRequest(
            String universityCode,
            String majorName,
            String campus,
            String subjectGroup,
            String admissionType) {
    }
}
