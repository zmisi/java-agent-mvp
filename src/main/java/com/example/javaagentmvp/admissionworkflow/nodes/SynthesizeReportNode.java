package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.format.RankResponseFormatter;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.javaagentmvp.observability.AgentMetrics;
import com.example.javaagentmvp.observability.TraceResponseFilter;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SynthesizeReportNode implements WorkflowNode {

    public static final String NAME = "synthesize_report";

    private static final Logger log = LoggerFactory.getLogger(SynthesizeReportNode.class);

    private final ChatClient workflowChatClient;
    private final AdmissionWorkflowProperties properties;
    private final String reportSystemPrompt;
    private final String rankSystemPrompt;
    private final AgentMetrics agentMetrics;
    private final ObservationRegistry observationRegistry;

    public SynthesizeReportNode(
            @Qualifier("workflowChatClient") ChatClient workflowChatClient,
            AdmissionWorkflowProperties properties,
            ResourceLoader resourceLoader,
            AgentMetrics agentMetrics,
            ObservationRegistry observationRegistry) {
        this.workflowChatClient = workflowChatClient;
        this.properties = properties;
        this.reportSystemPrompt = loadSystemPrompt(properties.synthesis().promptLocation(), resourceLoader);
        this.rankSystemPrompt = loadSystemPrompt(properties.synthesis().rankPromptLocation(), resourceLoader);
        this.agentMetrics = agentMetrics;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    @SuppressWarnings("unchecked")
    public WorkflowNodeResult execute(WorkflowContext context) {
        Map<String, Object> finalResult = context.get(FormatResponseNode.KEY_FINAL_RESULT, Map.class);
        if (finalResult == null) {
            return WorkflowNodeResult.skipped("no formatted result");
        }

        String summary = String.valueOf(finalResult.getOrDefault("summary", ""));

        AdmissionIntent intent = context.get(CompileQueryNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.UNKNOWN && !hasUsableData(context, finalResult)) {
            applyReport(finalResult, summary, false);
            return WorkflowNodeResult.skipped("unknown intent without data");
        }

        if (intent == AdmissionIntent.RANK) {
            String rankReport = formatRankReport(context, finalResult);
            if (rankReport != null) {
                applyReport(finalResult, rankReport, false);
                context.put(FormatResponseNode.KEY_FINAL_RESULT, finalResult);
                return WorkflowNodeResult.succeeded(Map.of(
                        "reportChars", rankReport.length(),
                        "usedLlm", false,
                        "deterministicRankFormat", true));
            }
        }

        if (!properties.synthesis().enabled()) {
            applyReport(finalResult, summary, false);
            return WorkflowNodeResult.skipped("synthesis disabled");
        }

        String userPrompt = buildUserPrompt(context, finalResult);
        String systemPrompt = resolveSystemPrompt(intent);
        String report;
        try {
            log.info("[WORKFLOW runId={}] synthesize_report request chars={} intent={}",
                    context.runId(), userPrompt.length(), intent);
            long startedAt = System.nanoTime();
            report = TraceResponseFilter.observe(
                    observationRegistry,
                    "agent.llm.synthesize",
                    "workflow",
                    () -> workflowChatClient.prompt()
                            .system(systemPrompt)
                            .user(userPrompt)
                            .call()
                            .content());
            agentMetrics.recordLlmCall("workflow", (System.nanoTime() - startedAt) / 1_000_000);
            if (report == null || report.isBlank()) {
                throw new IllegalStateException("empty synthesis response");
            }
            report = report.strip();
            log.info("[WORKFLOW runId={}] synthesize_report ok chars={}", context.runId(), report.length());
        }
        catch (RuntimeException ex) {
            if (!properties.synthesis().fallbackToSummaryOnFailure()) {
                throw ex;
            }
            log.warn("[WORKFLOW runId={}] synthesize_report failed, using summary fallback: {}",
                    context.runId(), ex.getMessage());
            report = summary.isBlank() ? "报告生成失败，请稍后重试。" : summary;
        }

        applyReport(finalResult, report, true);
        context.put(FormatResponseNode.KEY_FINAL_RESULT, finalResult);
        return WorkflowNodeResult.succeeded(Map.of(
                "reportChars", report.length(),
                "usedLlm", !report.equals(summary)));
    }

    static String buildUserPrompt(WorkflowContext context, Map<String, Object> finalResult) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户问题：\n").append(context.inputMessage()).append("\n\n");

        Object intent = finalResult.get("intent");
        if (intent != null) {
            prompt.append("识别意图：").append(intent).append("\n\n");
        }

        Object school = finalResult.get("school");
        if (school != null) {
            prompt.append("目标院校：").append(school).append("\n");
        }
        Object majorKeywords = finalResult.get("majorKeywords");
        if (majorKeywords != null) {
            prompt.append("专业关键词：").append(majorKeywords).append("\n");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> verification = context.get(VerifyAnswerNode.KEY_VERIFICATION, Map.class);
        if (verification != null && !verification.isEmpty()) {
            prompt.append("\n校验结果：").append(verification).append("\n");
        }

        Object scoreResult = finalResult.get("scoreResult");
        if (scoreResult != null) {
            prompt.append("\n--- 专业匹配（结构化） ---\n");
            prompt.append(formatScoreSection(scoreResult)).append("\n");
        }

        Object rankResult = finalResult.get("rankResult");
        if (rankResult != null) {
            prompt.append("\n--- 分数位次（结构化，均为已导入的官方一分一段表） ---\n");
            prompt.append(formatRankSection(rankResult)).append("\n");
        }

        Object totalBefore = finalResult.get("totalMatchedBeforeFilter");
        if (totalBefore != null) {
            prompt.append("筛选前全省可报专业总数：").append(totalBefore).append("\n");
        }

        Object policySources = finalResult.get("policySources");
        if (policySources instanceof List<?> sources && !sources.isEmpty()) {
            prompt.append("\n--- 政策/章程片段 ---\n");
            int index = 1;
            for (Object item : sources) {
                if (item instanceof RagSource source) {
                    prompt.append(index++).append(". ")
                            .append(source.title() == null ? "" : source.title());
                    if (source.school() != null && !source.school().isBlank()) {
                        prompt.append(" [").append(source.school()).append(']');
                    }
                    prompt.append("\n   ").append(source.snippet() == null ? "" : source.snippet())
                            .append("\n");
                }
            }
        }

        Object templateSummary = finalResult.get("summary");
        if (templateSummary != null && !String.valueOf(templateSummary).isBlank()) {
            prompt.append("\n--- 系统摘要（可作参考，请扩展为完整报告） ---\n")
                    .append(templateSummary)
                    .append('\n');
        }

        if (AdmissionIntent.RANK.name().equals(intent)) {
            prompt.append("\n请根据以上位次数据撰写完整回答。");
        }
        else {
            prompt.append("\n请撰写完整的志愿分析报告。");
        }
        return prompt.toString();
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static String formatRankReport(WorkflowContext context, Map<String, Object> finalResult) {
        Object rankResultObj = finalResult.get("rankResult");
        if (rankResultObj == null) {
            return null;
        }
        JsonNode rankResult = toJsonNode(rankResultObj);
        if (rankResult == null) {
            return null;
        }
        int count = rankResult.path("count").asInt(rankResult.path("ranks").size());
        if (count <= 0) {
            return null;
        }
        AdmissionQueryIr query = context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class);
        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(context.inputMessage());
        Integer score = query != null && query.slots().score() != null
                ? query.slots().score()
                : parsed.score();
        List<String> provinces = query != null ? query.slots().provincesOrEmpty() : List.of();
        String province = provinces.size() == 1
                ? provinces.get(0)
                : parsed.province();
        return RankResponseFormatter.format(rankResult, score, province);
    }

    private static JsonNode toJsonNode(Object value) {
        if (value instanceof JsonNode node) {
            return node;
        }
        return OBJECT_MAPPER.valueToTree(value);
    }

    private String resolveSystemPrompt(AdmissionIntent intent) {
        if (intent == AdmissionIntent.RANK) {
            return rankSystemPrompt;
        }
        return reportSystemPrompt;
    }

    private static String formatRankSection(Object rankResult) {
        if (rankResult instanceof JsonNode node) {
            return formatRankJsonNode(node);
        }
        if (rankResult instanceof Map<?, ?> map) {
            StringBuilder section = new StringBuilder();
            Object count = map.get("count");
            if (count != null) {
                section.append("位次记录数：").append(count).append("\n");
            }
            section.append(formatRankSamples(map.get("ranks")));
            return section.toString();
        }
        return String.valueOf(rankResult);
    }

    private static String formatRankJsonNode(JsonNode rankResult) {
        StringBuilder section = new StringBuilder();
        section.append("位次记录数：").append(rankResult.path("count").asInt(0)).append("\n");
        section.append(formatRankSamples(rankResult.get("ranks")));
        return section.toString();
    }

    private static String formatRankSamples(Object ranksObj) {
        List<String> samples = new ArrayList<>();
        if (ranksObj instanceof JsonNode ranksNode && ranksNode.isArray()) {
            for (JsonNode rank : ranksNode) {
                if (samples.size() >= 8) {
                    break;
                }
                samples.add(formatRankLine(rank));
            }
        }
        else if (ranksObj instanceof List<?> ranks) {
            for (Object rank : ranks) {
                if (samples.size() >= 8) {
                    break;
                }
                if (rank instanceof Map<?, ?> rankMap) {
                    samples.add(formatRankLineMap(rankMap));
                }
                else if (rank instanceof JsonNode rankNode) {
                    samples.add(formatRankLine(rankNode));
                }
            }
        }
        if (samples.isEmpty()) {
            return "位次样本：（无）\n";
        }
        return "位次样本（最多 8 条）：\n- " + String.join("\n- ", samples) + "\n";
    }

    private static String formatRankLine(JsonNode rank) {
        String rankText = text(rank.get("rank"));
        if (rankText.isBlank()) {
            rankText = text(rank.get("rank_min")) + "-" + text(rank.get("rank_max"));
        }
        StringBuilder line = new StringBuilder(String.format(
                "%s %s %s · 位次 %s",
                text(rank.get("year")),
                text(rank.get("province")),
                text(rank.get("subject_group")),
                rankText));
        String segmentCount = text(rank.get("segment_count"));
        if (!segmentCount.isBlank()) {
            line.append(" · 本段 ").append(segmentCount).append(" 人");
        }
        appendRankSource(line, text(rank.get("source_url")), text(rank.get("source_provider")));
        return line.toString();
    }

    private static String formatRankLineMap(Map<?, ?> rank) {
        String rankText = mapText(rank, "rank");
        if (rankText.isBlank()) {
            rankText = mapText(rank, "rank_min") + "-" + mapText(rank, "rank_max");
        }
        StringBuilder line = new StringBuilder(String.format(
                "%s %s %s · 位次 %s",
                mapText(rank, "year"),
                mapText(rank, "province"),
                mapText(rank, "subject_group"),
                rankText));
        String segmentCount = mapText(rank, "segment_count");
        if (!segmentCount.isBlank()) {
            line.append(" · 本段 ").append(segmentCount).append(" 人");
        }
        appendRankSource(line, mapText(rank, "source_url"), mapText(rank, "source_provider"));
        return line.toString();
    }

    private static void appendRankSource(StringBuilder line, String sourceUrl, String sourceProvider) {
        if (!sourceUrl.isBlank()) {
            line.append(" · 来源 ").append(sourceUrl);
        }
        else if (!sourceProvider.isBlank()) {
            line.append(" · 来源 ").append(sourceProvider);
        }
    }

    private static String formatScoreSection(Object scoreResult) {
        if (scoreResult instanceof JsonNode node) {
            return formatScoreJsonNode(node);
        }
        if (scoreResult instanceof Map<?, ?> map) {
            StringBuilder section = new StringBuilder();
            Object tierCounts = map.get("tier_counts");
            if (tierCounts != null) {
                section.append("冲/稳/保数量：").append(tierCounts).append("\n");
            }
            Object count = map.get("count");
            if (count != null) {
                section.append("匹配专业数：").append(count).append("\n");
            }
            section.append(formatMajorSamples(map.get("majors")));
            return section.toString();
        }
        return String.valueOf(scoreResult);
    }

    private static String formatScoreJsonNode(JsonNode scoreResult) {
        StringBuilder section = new StringBuilder();
        JsonNode tierCounts = scoreResult.get("tier_counts");
        if (tierCounts != null && !tierCounts.isNull()) {
            section.append("冲/稳/保数量：")
                    .append("冲 ").append(tierCounts.path("冲").asInt(0))
                    .append(" / 稳 ").append(tierCounts.path("稳").asInt(0))
                    .append(" / 保 ").append(tierCounts.path("保").asInt(0))
                    .append("\n");
        }
        section.append("匹配专业数：").append(scoreResult.path("count").asInt(0)).append("\n");
        section.append(formatMajorSamples(scoreResult.get("majors")));
        return section.toString();
    }

    private static String formatMajorSamples(Object majorsObj) {
        List<String> samples = new ArrayList<>();
        if (majorsObj instanceof JsonNode majorsNode && majorsNode.isArray()) {
            for (JsonNode major : majorsNode) {
                if (samples.size() >= 8) {
                    break;
                }
                samples.add(formatMajorLine(major));
            }
        }
        else if (majorsObj instanceof List<?> majors) {
            for (Object major : majors) {
                if (samples.size() >= 8) {
                    break;
                }
                if (major instanceof Map<?, ?> majorMap) {
                    samples.add(formatMajorLineMap(majorMap));
                }
                else if (major instanceof JsonNode majorNode) {
                    samples.add(formatMajorLine(majorNode));
                }
            }
        }
        if (samples.isEmpty()) {
            return "专业样本：（无）\n";
        }
        return "专业样本（最多 8 条）：\n- " + String.join("\n- ", samples) + "\n";
    }

    private static String formatMajorLine(JsonNode major) {
        return String.format(
                "%s · %s · 最低分 %s",
                text(major.get("university_name")),
                text(major.get("major_name")),
                text(major.get("min_score")));
    }

    private static String formatMajorLineMap(Map<?, ?> major) {
        return String.format(
                "%s · %s · 最低分 %s",
                mapText(major, "university_name"),
                mapText(major, "major_name"),
                mapText(major, "min_score"));
    }

    private static String mapText(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static boolean hasUsableData(WorkflowContext context, Map<String, Object> finalResult) {
        if (finalResult.get("scoreResult") != null || finalResult.get("rankResult") != null) {
            return true;
        }
        Object policySources = finalResult.get("policySources");
        if (policySources instanceof List<?> list && !list.isEmpty()) {
            return true;
        }
        JsonNode scoreResult = context.get(FilterScoreMajorsNode.KEY_FILTERED_SCORE_RESULT, JsonNode.class);
        if (scoreResult == null) {
            scoreResult = context.get(ScoreToolNode.KEY_SCORE_RESULT, JsonNode.class);
        }
        return scoreResult != null;
    }

    private static void applyReport(Map<String, Object> finalResult, String report, boolean llmGenerated) {
        Map<String, Object> updated = new LinkedHashMap<>(finalResult);
        updated.put("report", report);
        updated.put("assistant", report);
        updated.put("llmGenerated", llmGenerated);
        finalResult.clear();
        finalResult.putAll(updated);
    }

    private static String loadSystemPrompt(String location, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Workflow synthesis prompt not found: " + location);
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).strip();
        }
        catch (Exception ex) {
            throw new IllegalStateException("Failed to load workflow synthesis prompt: " + location, ex);
        }
    }
}
