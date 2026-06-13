package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final String systemPrompt;

    public SynthesizeReportNode(
            @Qualifier("workflowChatClient") ChatClient workflowChatClient,
            AdmissionWorkflowProperties properties,
            ResourceLoader resourceLoader) {
        this.workflowChatClient = workflowChatClient;
        this.properties = properties;
        this.systemPrompt = loadSystemPrompt(properties.synthesis().promptLocation(), resourceLoader);
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
        if (!properties.synthesis().enabled()) {
            applyReport(finalResult, summary, false);
            return WorkflowNodeResult.skipped("synthesis disabled");
        }

        AdmissionIntent intent = context.get(IntentClassifyNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.UNKNOWN && !hasUsableData(context, finalResult)) {
            applyReport(finalResult, summary, false);
            return WorkflowNodeResult.skipped("unknown intent without data");
        }

        String userPrompt = buildUserPrompt(context, finalResult);
        String report;
        try {
            log.info("[WORKFLOW runId={}] synthesize_report request chars={}",
                    context.runId(), userPrompt.length());
            report = workflowChatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();
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

        prompt.append("\n请撰写完整的志愿分析报告。");
        return prompt.toString();
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
        if (finalResult.get("scoreResult") != null) {
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
