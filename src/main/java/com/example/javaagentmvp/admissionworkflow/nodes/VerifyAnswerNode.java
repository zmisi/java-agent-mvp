package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class VerifyAnswerNode implements WorkflowNode {

    public static final String NAME = "verify_answer";
    public static final String KEY_VERIFICATION = "verification";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        JsonNode scoreResult = effectiveScoreResult(context);
        @SuppressWarnings("unchecked")
        List<RagSource> policySources = (List<RagSource>) context.get(PolicyRagNode.KEY_POLICY_SOURCES);
        if (policySources == null) {
            policySources = List.of();
        }
        AdmissionQueryHints.Hints hints =
                context.get(FilterScoreMajorsNode.KEY_QUERY_HINTS, AdmissionQueryHints.Hints.class);

        List<String> issues = new ArrayList<>();
        List<String> followUpFields = new ArrayList<>();

        if (intent == AdmissionIntent.RANK) {
            JsonNode rankResult = context.get(ScoreToolNode.KEY_RANK_RESULT, JsonNode.class);
            if (rankResult == null) {
                Object missing = context.get("missingFields");
                if (missing != null) {
                    followUpFields.add(String.valueOf(missing));
                }
                else {
                    issues.add("rank tool returned no data");
                }
            }
            else {
                int count = rankResult.path("count").asInt(rankResult.path("ranks").size());
                if (count <= 0) {
                    issues.add("no rank matched the given score");
                }
            }
        }

        if (intent == AdmissionIntent.SCORE || intent == AdmissionIntent.REPORT) {
            if (scoreResult == null) {
                Object missing = context.get("missingFields");
                if (missing != null) {
                    followUpFields.add(String.valueOf(missing));
                }
                else {
                    issues.add("score tool returned no data");
                }
            }
            else {
                int count = scoreResult.path("count").asInt(scoreResult.path("majors").size());
                if (count <= 0) {
                    if (hints != null && (hints.schoolSpecified() || hints.majorSpecified())) {
                        issues.add("no majors matched after school/major filter");
                    }
                    else {
                        issues.add("no majors matched the given score");
                    }
                }
            }
        }

        if (intent == AdmissionIntent.POLICY || intent == AdmissionIntent.REPORT) {
            if (policySources.isEmpty()) {
                issues.add("no policy documents retrieved");
            }
        }

        if (intent == AdmissionIntent.UNKNOWN && scoreResult == null && policySources.isEmpty()) {
            issues.add("unable to classify intent or retrieve supporting data");
        }

        Map<String, Object> verification = Map.of(
                "valid", issues.isEmpty(),
                "issues", issues,
                "followUpFields", followUpFields);
        context.put(KEY_VERIFICATION, verification);

        if (!issues.isEmpty() && !followUpFields.isEmpty()) {
            return WorkflowNodeResult.succeeded(verification);
        }
        if (!issues.isEmpty()) {
            return WorkflowNodeResult.failed(String.join("; ", issues));
        }
        return WorkflowNodeResult.succeeded(verification);
    }

    private static JsonNode effectiveScoreResult(WorkflowContext context) {
        JsonNode filtered = context.get(FilterScoreMajorsNode.KEY_FILTERED_SCORE_RESULT, JsonNode.class);
        if (filtered != null) {
            return filtered;
        }
        return context.get(ScoreToolNode.KEY_SCORE_RESULT, JsonNode.class);
    }
}
