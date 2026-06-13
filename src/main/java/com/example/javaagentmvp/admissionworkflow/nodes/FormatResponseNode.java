package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class FormatResponseNode implements WorkflowNode {

    public static final String NAME = "format_response";
    public static final String KEY_FINAL_RESULT = "finalResult";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        JsonNode rawScoreResult = context.get(ScoreToolNode.KEY_SCORE_RESULT, JsonNode.class);
        JsonNode scoreResult = context.get(FilterScoreMajorsNode.KEY_FILTERED_SCORE_RESULT, JsonNode.class);
        if (scoreResult == null) {
            scoreResult = rawScoreResult;
        }
        AdmissionQueryHints.Hints hints =
                context.get(FilterScoreMajorsNode.KEY_QUERY_HINTS, AdmissionQueryHints.Hints.class);
        @SuppressWarnings("unchecked")
        List<RagSource> policySources = (List<RagSource>) context.get(PolicyRagNode.KEY_POLICY_SOURCES);
        if (policySources == null) {
            policySources = List.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intent == null ? AdmissionIntent.UNKNOWN.name() : intent.name());
        result.put("summary", buildSummary(intent, scoreResult, rawScoreResult, policySources, hints));
        if (scoreResult != null) {
            result.put("scoreResult", scoreResult);
        }
        if (rawScoreResult != null && rawScoreResult != scoreResult) {
            result.put("totalMatchedBeforeFilter", rawScoreResult.path("count").asInt());
        }
        if (!policySources.isEmpty()) {
            result.put("policySources", policySources);
        }
        if (hints != null && hints.schoolSpecified()) {
            hints.primarySchool().ifPresent(school -> result.put("school", school.displayName()));
        }
        if (hints != null && !hints.majorKeywords().isEmpty()) {
            result.put("majorKeywords", hints.majorKeywords());
        }

        context.put(KEY_FINAL_RESULT, result);
        return WorkflowNodeResult.succeeded(Map.of("summary", result.get("summary")));
    }

    private static String buildSummary(
            AdmissionIntent intent,
            JsonNode scoreResult,
            JsonNode rawScoreResult,
            List<RagSource> policySources,
            AdmissionQueryHints.Hints hints) {
        if (intent == null) {
            return "未能识别问题意图。";
        }
        return switch (intent) {
            case SCORE -> buildScoreSummary(scoreResult, hints);
            case POLICY -> policySources.isEmpty()
                    ? "未检索到相关招生政策文档。"
                    : "已检索到 " + policySources.size() + " 条政策/章程片段，详见 policySources。";
            case REPORT -> buildReportSummary(scoreResult, rawScoreResult, policySources, hints);
            case UNKNOWN -> "请补充高考分数、省份或想了解的招生政策关键词。";
        };
    }

    private static String buildScoreSummary(JsonNode scoreResult, AdmissionQueryHints.Hints hints) {
        int count = majorCount(scoreResult);
        if (count <= 0) {
            return "未能查询到符合条件的专业，请补充省份、科类或分数。";
        }
        if (hints != null && hints.schoolSpecified()) {
            return describeFilteredScore(scoreResult, hints) + "。";
        }
        return "已查询到 " + count + " 个可报专业，详见 scoreResult。";
    }

    private static String buildReportSummary(
            JsonNode scoreResult,
            JsonNode rawScoreResult,
            List<RagSource> policySources,
            AdmissionQueryHints.Hints hints) {
        StringBuilder summary = new StringBuilder("综合报告：");
        if (hints != null && (hints.schoolSpecified() || hints.majorSpecified())) {
            summary.append(describeFilteredScore(scoreResult, hints));
            int total = rawScoreResult == null ? 0 : rawScoreResult.path("count").asInt();
            if (total > 0 && total != majorCount(scoreResult)) {
                summary.append("（全省共 ").append(total).append(" 条可报专业）");
            }
        }
        else {
            summary.append("匹配专业 ").append(majorCount(scoreResult)).append(" 条");
        }
        summary.append("，政策片段 ").append(policySources.size()).append(" 条");
        if (!policySources.isEmpty()) {
            String titles = policySources.stream()
                    .map(RagSource::title)
                    .limit(3)
                    .collect(Collectors.joining("、"));
            summary.append("（").append(titles).append("）");
        }
        summary.append('。');
        return summary.toString();
    }

    private static String describeFilteredScore(JsonNode scoreResult, AdmissionQueryHints.Hints hints) {
        String school = hints.primarySchool().map(s -> s.displayName()).orElse("目标院校");
        String majors = hints.majorKeywords().isEmpty()
                ? "相关专业"
                : String.join("、", hints.majorKeywords());
        int count = majorCount(scoreResult);
        JsonNode tierCounts = scoreResult == null ? null : scoreResult.get("tier_counts");
        if (tierCounts != null && count > 0) {
            return String.format(
                    "%s 在 %s 方向匹配 %d 条（冲 %d / 稳 %d / 保 %d）",
                    school,
                    majors,
                    count,
                    tierCounts.path("冲").asInt(0),
                    tierCounts.path("稳").asInt(0),
                    tierCounts.path("保").asInt(0));
        }
        return String.format("%s 在 %s 方向匹配 %d 条专业", school, majors, count);
    }

    private static int majorCount(JsonNode scoreResult) {
        if (scoreResult == null) {
            return 0;
        }
        return scoreResult.path("count").asInt(scoreResult.path("majors").size());
    }
}
