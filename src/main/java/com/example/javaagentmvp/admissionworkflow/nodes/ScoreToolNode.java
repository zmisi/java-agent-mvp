package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.tool.AdmissionScoreToolClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ScoreToolNode implements WorkflowNode {

    public static final String NAME = "score_tool";
    public static final String KEY_SCORE_RESULT = "scoreResult";
    public static final String KEY_RANK_RESULT = "rankResult";

    private final AdmissionScoreToolClient admissionScoreToolClient;

    public ScoreToolNode(AdmissionScoreToolClient admissionScoreToolClient) {
        this.admissionScoreToolClient = admissionScoreToolClient;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.POLICY) {
            return WorkflowNodeResult.skipped("policy-only intent");
        }

        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(context.inputMessage());
        if (intent == AdmissionIntent.RANK) {
            return executeRankQuery(context, parsed);
        }

        String missing = AdmissionInputParser.describeMissingFields(parsed);
        if (missing != null) {
            if (intent == AdmissionIntent.SCORE || intent == AdmissionIntent.REPORT) {
                context.put("missingFields", missing);
                return WorkflowNodeResult.skipped("missing required field: " + missing);
            }
            return WorkflowNodeResult.skipped("no score query parameters");
        }

        try {
            JsonNode result = admissionScoreToolClient.getMajorByScore(
                    context.runId(),
                    parsed.score(),
                    parsed.province(),
                    parsed.subjectGroup(),
                    parsed.year(),
                    parsed.admissionType());
            context.put(KEY_SCORE_RESULT, result);
            int count = result.path("count").asInt(result.path("majors").size());
            return WorkflowNodeResult.succeeded(Map.of(
                    "count", count,
                    "score", parsed.score(),
                    "mcpQueryScore", AdmissionScoreToolClient.mcpQueryScore(parsed.score()),
                    "province", parsed.province(),
                    "subjectGroup", parsed.subjectGroup() == null ? "" : parsed.subjectGroup(),
                    "year", parsed.year() == null ? "" : parsed.year(),
                    "admissionType", parsed.admissionType() == null ? "" : parsed.admissionType()));
        }
        catch (RuntimeException ex) {
            return WorkflowNodeResult.failed(ex.getMessage());
        }
    }

    private WorkflowNodeResult executeRankQuery(WorkflowContext context, AdmissionInputParser.ParsedAdmissionInput parsed) {
        if (parsed.score() == null) {
            context.put("missingFields", "score");
            return WorkflowNodeResult.skipped("missing required field: score");
        }

        try {
            JsonNode result = admissionScoreToolClient.getRankByScore(
                    context.runId(),
                    parsed.score(),
                    parsed.province(),
                    parsed.subjectGroup(),
                    parsed.year());
            context.put(KEY_RANK_RESULT, result);
            int count = result.path("count").asInt(result.path("ranks").size());
            return WorkflowNodeResult.succeeded(Map.of(
                    "count", count,
                    "score", parsed.score(),
                    "province", parsed.province() == null ? "" : parsed.province(),
                    "subjectGroup", parsed.subjectGroup() == null ? "" : parsed.subjectGroup(),
                    "year", parsed.year() == null ? "" : parsed.year()));
        }
        catch (RuntimeException ex) {
            return WorkflowNodeResult.failed(ex.getMessage());
        }
    }
}
