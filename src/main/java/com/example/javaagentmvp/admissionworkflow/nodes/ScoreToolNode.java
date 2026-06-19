package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.tool.AdmissionScoreToolClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ScoreToolNode implements WorkflowNode {

    public static final String NAME = "score_tool";
    public static final String KEY_SCORE_RESULT = "scoreResult";
    public static final String KEY_RANK_RESULT = "rankResult";

    private final AdmissionScoreToolClient admissionScoreToolClient;
    private final ObjectMapper objectMapper;

    public ScoreToolNode(AdmissionScoreToolClient admissionScoreToolClient, ObjectMapper objectMapper) {
        this.admissionScoreToolClient = admissionScoreToolClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        if (context.get(CompileQueryNode.KEY_CLARIFICATION_MESSAGE, String.class) != null) {
            return WorkflowNodeResult.skipped("awaiting clarification");
        }

        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.POLICY) {
            return WorkflowNodeResult.skipped("policy-only intent");
        }

        AdmissionSlotsIr slots = resolveSlots(context);
        if (intent == AdmissionIntent.RANK) {
            return executeRankQuery(context, slots);
        }

        String missing = describeMissingFields(slots);
        if (missing != null) {
            if (intent == AdmissionIntent.SCORE || intent == AdmissionIntent.REPORT) {
                context.put("missingFields", missing);
                return WorkflowNodeResult.skipped("missing required field: " + missing);
            }
            return WorkflowNodeResult.skipped("no score query parameters");
        }

        try {
            List<String> provinces = provincesForQuery(slots);
            List<JsonNode> provinceResults = new ArrayList<>();
            for (String province : provinces) {
                JsonNode result = admissionScoreToolClient.getMajorByScore(
                        context.runId(),
                        slots.score(),
                        province,
                        slots.subjectGroup(),
                        slots.year(),
                        slots.admissionType());
                provinceResults.add(tagProvince(result, province));
            }
            JsonNode merged = mergeScoreResults(provinceResults);
            context.put(KEY_SCORE_RESULT, merged);
            int count = merged.path("count").asInt(merged.path("majors").size());
            return WorkflowNodeResult.succeeded(Map.of(
                    "count", count,
                    "score", slots.score(),
                    "mcpQueryScore", AdmissionScoreToolClient.mcpQueryScore(slots.score()),
                    "provinces", provinces,
                    "subjectGroup", slots.subjectGroup() == null ? "" : slots.subjectGroup(),
                    "year", slots.year() == null ? "" : slots.year(),
                    "admissionType", slots.admissionType() == null ? "" : slots.admissionType()));
        }
        catch (RuntimeException ex) {
            return WorkflowNodeResult.failed(ex.getMessage());
        }
    }

    private WorkflowNodeResult executeRankQuery(WorkflowContext context, AdmissionSlotsIr slots) {
        if (slots.score() == null) {
            context.put("missingFields", "score");
            return WorkflowNodeResult.skipped("missing required field: score");
        }

        try {
            JsonNode result = admissionScoreToolClient.getRankByScore(
                    context.runId(),
                    slots.score(),
                    slots.primaryProvince(),
                    slots.subjectGroup(),
                    slots.year());
            context.put(KEY_RANK_RESULT, result);
            int count = result.path("count").asInt(result.path("ranks").size());
            return WorkflowNodeResult.succeeded(Map.of(
                    "count", count,
                    "score", slots.score(),
                    "province", slots.primaryProvince() == null ? "" : slots.primaryProvince(),
                    "subjectGroup", slots.subjectGroup() == null ? "" : slots.subjectGroup(),
                    "year", slots.year() == null ? "" : slots.year()));
        }
        catch (RuntimeException ex) {
            return WorkflowNodeResult.failed(ex.getMessage());
        }
    }

    private static AdmissionSlotsIr resolveSlots(WorkflowContext context) {
        AdmissionQueryIr query = context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class);
        if (query != null && query.slots() != null) {
            return query.slots();
        }
        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(context.inputMessage());
        List<String> provinces = parsed.province() == null ? List.of() : List.of(parsed.province());
        return new AdmissionSlotsIr(
                parsed.score(),
                provinces,
                parsed.subjectGroup(),
                parsed.year(),
                parsed.admissionType());
    }

    private static List<String> provincesForQuery(AdmissionSlotsIr slots) {
        if (!slots.provincesOrEmpty().isEmpty()) {
            return slots.provincesOrEmpty();
        }
        if (slots.primaryProvince() != null) {
            return List.of(slots.primaryProvince());
        }
        return List.of();
    }

    private static String describeMissingFields(AdmissionSlotsIr slots) {
        if (slots.score() == null) {
            return "score";
        }
        if (slots.provincesOrEmpty().isEmpty()) {
            return "provinces";
        }
        return null;
    }

    private JsonNode tagProvince(JsonNode result, String province) {
        if (!(result instanceof ObjectNode objectNode)) {
            return result;
        }
        objectNode.put("query_province", province);
        if (result.path("majors").isArray()) {
            for (JsonNode major : result.path("majors")) {
                if (major instanceof ObjectNode majorNode) {
                    majorNode.put("query_province", province);
                }
            }
        }
        return objectNode;
    }

    private JsonNode mergeScoreResults(List<JsonNode> provinceResults) {
        ObjectNode merged = objectMapper.createObjectNode();
        ArrayNode majors = objectMapper.createArrayNode();
        int totalCount = 0;
        for (JsonNode provinceResult : provinceResults) {
            totalCount += provinceResult.path("count").asInt(0);
            JsonNode majorsNode = provinceResult.path("majors");
            if (majorsNode.isArray()) {
                majorsNode.forEach(majors::add);
            }
        }
        merged.put("count", majors.size());
        merged.put("totalBeforeMerge", totalCount);
        merged.set("majors", majors);
        if (!provinceResults.isEmpty()) {
            merged.put("user_score", provinceResults.get(0).path("user_score").asInt());
            merged.put("tier_delta", provinceResults.get(0).path("tier_delta").asInt());
            merged.put("mcp_query_score", provinceResults.get(0).path("mcp_query_score").asInt());
        }
        return merged;
    }
}
