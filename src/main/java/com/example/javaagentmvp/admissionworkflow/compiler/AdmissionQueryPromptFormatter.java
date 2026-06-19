package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.format.RankSubjectGroupResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Formats compiled admission IR into a system-prompt task block for chat. */
public final class AdmissionQueryPromptFormatter {

    private AdmissionQueryPromptFormatter() {
    }

    public static boolean needsTaskPrompt(AdmissionQueryIr query) {
        if (query == null) {
            return false;
        }
        AdmissionIntent intent = query.toIntent();
        if (intent != AdmissionIntent.UNKNOWN) {
            return true;
        }
        return hasConstraints(query);
    }

    public static String format(AdmissionQueryIr query) {
        if (query == null || !needsTaskPrompt(query)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 当前对话任务（Query Compiler 解析，请严格遵循）\n");
        sb.append("- 任务: ").append(taskLabel(query.task())).append('\n');

        AdmissionIntent intent = query.toIntent();
        if (intent == AdmissionIntent.POLICY) {
            sb.append("- 说明: 政策/简章问题，勿调用 MCP 分数工具\n");
            sb.append('\n');
            sb.append("**必须**依据 RAG 知识库片段回答；**禁止**调用 getMajorByScore / getRankByScore。\n");
            return sb.toString().strip();
        }

        AdmissionSlotsIr slots = query.slots();
        appendSlot(sb, "分数", slots.score() != null ? slots.score() + "分" : null);
        if (!slots.provincesOrEmpty().isEmpty()) {
            sb.append("- 省份: ").append(String.join("、", slots.provincesOrEmpty())).append('\n');
        }
        else {
            sb.append("- 省份: （未指定）\n");
        }
        appendSlot(sb, "科类", formatRankSubjectGroup(slots, intent));
        appendSlot(sb, "年份", slots.year() != null ? String.valueOf(slots.year()) : null);
        appendSlot(sb, "批次", slots.admissionType());

        if (!query.regions().isEmpty()) {
            String regions = query.regions().stream()
                    .map(region -> region.phrase() + "→" + String.join("/", region.provinces()))
                    .collect(Collectors.joining("；"));
            sb.append("- 区域: ").append(regions).append('\n');
        }

        appendFilters(sb, query.filters());
        appendPreferences(sb, query.preferences());

        sb.append('\n');
        if (intent == AdmissionIntent.RANK) {
            sb.append("**必须**调用 getRankByScore，参数见上。\n");
        }
        else if (intent == AdmissionIntent.SCORE || intent == AdmissionIntent.REPORT) {
            sb.append("**必须**调用 getMajorByScore，参数见上；系统会按冲/稳/保分层并应用排除条件。\n");
            if (slots.provincesOrEmpty().size() > 1) {
                sb.append("涉及多省查询时，工具会自动合并各省结果。\n");
            }
        }
        else if (hasConstraints(query)) {
            sb.append("用户已表达择校/专业约束，回答时需体现排除项与偏好。\n");
        }
        return sb.toString().strip();
    }

    private static boolean hasConstraints(AdmissionQueryIr query) {
        AdmissionFiltersIr filters = query.filters();
        return !query.regions().isEmpty()
                || !query.preferences().isEmpty()
                || !filters.excludeSchoolNameContains().isEmpty()
                || !filters.excludeMajorKeywords().isEmpty()
                || !filters.includeMajorKeywords().isEmpty();
    }

    private static String taskLabel(String task) {
        return switch (task == null ? "" : task) {
            case "search_majors" -> "查专业/院校";
            case "search_rank" -> "查位次";
            case "policy_qa" -> "政策问答";
            case "report" -> "综合报告";
            default -> "未知";
        };
    }

    private static String formatRankSubjectGroup(AdmissionSlotsIr slots, AdmissionIntent intent) {
        if (intent != AdmissionIntent.RANK || slots.provincesOrEmpty().size() <= 1) {
            return slots.subjectGroup();
        }
        boolean hasComprehensive = slots.provincesOrEmpty().stream()
                .anyMatch(RankSubjectGroupResolver::usesComprehensiveSubjectGroup);
        boolean hasSplit = slots.provincesOrEmpty().stream()
                .anyMatch(province -> !RankSubjectGroupResolver.usesComprehensiveSubjectGroup(province));
        if (hasComprehensive && hasSplit) {
            String splitLabel = slots.subjectGroup() != null && !slots.subjectGroup().isBlank()
                    ? slots.subjectGroup()
                    : "物理类/历史类";
            return splitLabel + "（分文理省）；浙沪京等查综合类";
        }
        return slots.subjectGroup();
    }

    private static void appendSlot(StringBuilder sb, String label, String value) {
        sb.append("- ").append(label).append(": ");
        if (value == null || value.isBlank()) {
            sb.append("（未指定）");
        }
        else {
            sb.append(value);
        }
        sb.append('\n');
    }

    private static void appendFilters(StringBuilder sb, AdmissionFiltersIr filters) {
        if (filters == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (!filters.excludeSchoolNameContains().isEmpty()) {
            parts.add("排除院校含「" + String.join("、", filters.excludeSchoolNameContains()) + "」");
        }
        if (!filters.excludeMajorKeywords().isEmpty()) {
            parts.add("排除专业含「" + String.join("、", filters.excludeMajorKeywords()) + "」");
        }
        if (!filters.includeMajorKeywords().isEmpty()) {
            parts.add("限定专业含「" + String.join("、", filters.includeMajorKeywords()) + "」");
        }
        if (!parts.isEmpty()) {
            sb.append("- 筛选: ").append(String.join("；", parts)).append('\n');
        }
    }

    private static void appendPreferences(StringBuilder sb, List<AdmissionPreferenceIr> preferences) {
        if (preferences == null || preferences.isEmpty()) {
            return;
        }
        List<String> labels = preferences.stream()
                .map(AdmissionQueryPromptFormatter::preferenceLabel)
                .toList();
        sb.append("- 偏好: ").append(String.join("、", labels)).append('\n');
    }

    private static String preferenceLabel(AdmissionPreferenceIr preference) {
        return switch (preference.dimension()) {
            case "employment_outlook" -> "就业前景";
            case "salary" -> "收入水平";
            case "state_owned_employability" -> "央国企就业";
            default -> preference.rawPhrase() != null ? preference.rawPhrase() : preference.dimension();
        };
    }
}
