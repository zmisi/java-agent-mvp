package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.filter.MajorScoreFilter;
import com.example.javaagentmvp.admissionworkflow.filter.PreferenceRanker;
import com.example.javaagentmvp.admissionworkflow.filter.QueryConstraints;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class FilterScoreMajorsNode implements WorkflowNode {

    public static final String NAME = "filter_score_majors";
    public static final String KEY_QUERY_HINTS = "queryHints";
    public static final String KEY_QUERY_CONSTRAINTS = "queryConstraints";
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
        if (context.get(CompileQueryNode.KEY_CLARIFICATION_MESSAGE, String.class) != null) {
            return WorkflowNodeResult.skipped("awaiting clarification");
        }

        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.RANK) {
            return WorkflowNodeResult.skipped("rank query — no major filtering");
        }

        AdmissionQueryIr query = context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class);
        AdmissionQueryHints.Hints hints = AdmissionQueryHints.parse(context.inputMessage(), ragProperties);
        if (query != null && !query.filters().includeMajorKeywords().isEmpty()) {
            hints = new AdmissionQueryHints.Hints(
                    hints.schools(),
                    query.filters().includeMajorKeywords(),
                    hints.schoolSpecified(),
                    true);
        }
        QueryConstraints constraints = QueryConstraints.fromIr(query, hints);
        context.put(KEY_QUERY_HINTS, hints);
        context.put(KEY_QUERY_CONSTRAINTS, constraints);

        JsonNode rawScoreResult = context.get(ScoreToolNode.KEY_SCORE_RESULT, JsonNode.class);
        if (rawScoreResult == null) {
            return WorkflowNodeResult.skipped("no score result to filter");
        }

        Integer score = resolveScore(context, query);
        if (score == null) {
            context.put(KEY_FILTERED_SCORE_RESULT, rawScoreResult);
            return WorkflowNodeResult.skipped("no score for tier classification");
        }

        MajorScoreFilter.FilterResult filtered =
                MajorScoreFilter.filter(rawScoreResult, score, hints, constraints, objectMapper);
        ObjectNode payload = filtered.payload();
        @SuppressWarnings("unchecked")
        List<RagSource> preferenceSources =
                (List<RagSource>) context.get(PreferenceRagNode.KEY_PREFERENCE_SOURCES);
        if (constraints.hasPreferenceRanking()) {
            PreferenceRanker.apply(payload, constraints, preferenceSources);
        }
        context.put(KEY_FILTERED_SCORE_RESULT, payload);

        boolean constraintOnly = constraints.hasExclusions()
                || constraints.hasProvinceFilter()
                || constraints.hasPreferenceRanking();
        if (!hints.schoolSpecified() && !hints.majorSpecified() && !constraintOnly) {
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
                "majorKeywords", hints.majorKeywords(),
                "provinces", constraints.provinces(),
                "excludedSchoolTokens", constraints.excludeSchoolNameContains(),
                "preferenceRanked", constraints.hasPreferenceRanking()));
    }

    private static Integer resolveScore(WorkflowContext context, AdmissionQueryIr query) {
        if (query != null && query.slots().score() != null) {
            return query.slots().score();
        }
        return AdmissionInputParser.parse(context.inputMessage()).score();
    }
}
