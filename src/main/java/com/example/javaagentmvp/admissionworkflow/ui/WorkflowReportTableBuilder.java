package com.example.javaagentmvp.admissionworkflow.ui;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowReportTableBuilder {

    private static final List<String> TIER_LABELS = List.of("冲", "稳", "保");

    private final McpTableExtractor mcpTableExtractor;
    private final ObjectMapper objectMapper;

    public WorkflowReportTableBuilder(McpTableExtractor mcpTableExtractor, ObjectMapper objectMapper) {
        this.mcpTableExtractor = mcpTableExtractor;
        this.objectMapper = objectMapper;
    }

    public List<ChatTable> buildTables(String inputMessage, Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return List.of();
        }
        Object scoreResultObj = result.get("scoreResult");
        if (scoreResultObj == null) {
            return List.of();
        }
        JsonNode scoreResult = objectMapper.valueToTree(scoreResultObj);
        JsonNode majorsByTier = scoreResult.get("majors_by_tier");
        if (majorsByTier == null || !majorsByTier.isObject()) {
            return List.of();
        }

        String toolInput = buildToolInput(inputMessage);
        List<ChatTable> tables = new ArrayList<>();
        for (String tier : TIER_LABELS) {
            JsonNode majors = majorsByTier.get(tier);
            if (majors == null || !majors.isArray()) {
                majors = objectMapper.createArrayNode();
            }
            mcpTableExtractor.extractMajorByScoreFromMajors(toolInput, majors, tier)
                    .ifPresent(tables::add);
        }
        return tables;
    }

    private String buildToolInput(String inputMessage) {
        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(inputMessage);
        ObjectNode node = objectMapper.createObjectNode();
        if (parsed.score() != null) {
            node.put("score", parsed.score());
        }
        if (parsed.province() != null) {
            node.put("province", parsed.province());
        }
        if (parsed.subjectGroup() != null) {
            node.put("subject_group", parsed.subjectGroup());
        }
        if (parsed.year() != null) {
            node.put("year", parsed.year());
        }
        if (parsed.admissionType() != null) {
            node.put("admission_type", parsed.admissionType());
        }
        try {
            return objectMapper.writeValueAsString(node);
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to build workflow tool input JSON", ex);
        }
    }
}
