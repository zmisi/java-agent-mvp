package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionQueryPromptFormatterTest {

    @Test
    void formatsComplexQueryWithFiltersAndPreferences() {
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(630, null, List.of("江苏", "浙江", "上海"), "物理类", 2025, "普通批"),
                new AdmissionFiltersIr(List.of("师范"), List.of("师范"), List.of(), List.of(), List.of(), List.of()),
                List.of(new AdmissionPreferenceIr("employment_outlook", 1.0, "就业前景")),
                List.of(new AdmissionRegionIr("长三角", List.of("江苏", "浙江", "上海"))),
                List.of(),
                List.of(),
                0.95,
                "长三角不当老师",
                null);

        String prompt = AdmissionQueryPromptFormatter.format(query);

        assertThat(prompt).contains("查专业/院校");
        assertThat(prompt).contains("江苏、浙江、上海");
        assertThat(prompt).contains("排除院校含「师范」");
        assertThat(prompt).contains("就业前景");
        assertThat(query.toIntent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(prompt).doesNotContain("**必须**调用 getMajorByScore");
    }

    @Test
    void formatsPostMcpSynthesisWithoutToolCallInstructions() {
        AdmissionQueryIr query = majorQuery();
        String prompt = AdmissionQueryPromptFormatter.formatPostMcpSynthesis(
                query,
                "只写导语，勿编造数据。");

        assertThat(prompt).contains("系统已预执行 MCP");
        assertThat(prompt).contains("禁止**再次调用");
        assertThat(prompt).contains("只写导语");
    }

    private static AdmissionQueryIr majorQuery() {
        return new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(630, null, List.of("安徽"), "物理类", 2025, "普通批"),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "安徽630分",
                null);
    }
}
