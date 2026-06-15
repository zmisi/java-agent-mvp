package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.filter.MajorScoreFilter;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class FilterScoreMajorsNode implements WorkflowNode {

    public static final String NAME = "filter_score_majors";
    public static final String KEY_QUERY_HINTS = "queryHints";
    public static final String KEY_FILTERED_SCORE_RESULT = "filteredScoreResult";

    private final RagProperties ragProperties;
    private final ObjectMapper objectMapper;

    public FilterScoreMajorsNode(RagProperties ragProperties, ObjectMapper objectMapper) {
        this.ragProperties = ragProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.RANK) {
            return WorkflowNodeResult.skipped("rank query — no major filtering");
        }

        AdmissionQueryHints.Hints hints = AdmissionQueryHints.parse(context.inputMessage(), ragProperties);
        context.put(KEY_QUERY_HINTS, hints);

        JsonNode rawScoreResult = context.get(ScoreToolNode.KEY_SCORE_RESULT, JsonNode.class);
        if (rawScoreResult == null) {
            return WorkflowNodeResult.skipped("no score result to filter");
        }

        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(context.inputMessage());
        Integer score = parsed.score();
        if (score == null) {
            context.put(KEY_FILTERED_SCORE_RESULT, rawScoreResult);
            return WorkflowNodeResult.skipped("no score for tier classification");
        }

        MajorScoreFilter.FilterResult filtered =
                MajorScoreFilter.filter(rawScoreResult, score, hints, objectMapper);
        context.put(KEY_FILTERED_SCORE_RESULT, filtered.payload());

        boolean tierOnly = !hints.schoolSpecified() && !hints.majorSpecified();
        if (tierOnly) {
            return WorkflowNodeResult.succeeded(Map.of(
                    "matchedCount", filtered.matchedCount(),
                    "totalCount", filtered.totalCount(),
                    "tierCounts", filtered.tierCounts(),
                    "tierOnly", true));
        }

        return WorkflowNodeResult.succeeded(Map.of(
                "matchedCount", filtered.matchedCount(),
                "totalCount", filtered.totalCount(),
                "schoolFiltered", filtered.schoolFiltered(),
                "majorFiltered", filtered.majorFiltered(),
                "tierCounts", filtered.tierCounts(),
                "schoolKey", hints.primarySchool().map(RagProperties.School::key).orElse(""),
                "majorKeywords", hints.majorKeywords()));
    }
}
