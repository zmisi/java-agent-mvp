package com.example.javaagentmvp.admissionworkflow.ui;

import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReportTableBuilderTest {

    private final WorkflowReportTableBuilder builder =
            new WorkflowReportTableBuilder(new McpTableExtractor(new ObjectMapper()), new ObjectMapper());

    @Test
    void buildsTierTablesFromMajorsByTier() {
        Map<String, Object> result = Map.of(
                "scoreResult",
                Map.of(
                        "count", 1,
                        "majors_by_tier",
                        Map.of(
                                "冲", List.of(),
                                "稳",
                                List.of(Map.of(
                                        "university_name", "合肥工业大学",
                                        "major_name", "软件工程",
                                        "campus", "宣城",
                                        "min_score", "622",
                                        "min_rank", "12000",
                                        "max_score", "630",
                                        "year", 2025,
                                        "subject_group", "物理类",
                                        "admission_type", "普通批")),
                                "保", List.of())));

        List<ChatTable> tables = builder.buildTables("安徽物理类630分", result);

        assertThat(tables).hasSize(3);
        assertThat(tables.get(0).title()).startsWith("冲");
        assertThat(tables.get(1).title()).startsWith("稳");
        assertThat(tables.get(1).rows()).hasSize(1);
        assertThat(tables.get(2).title()).startsWith("保");
    }

    @Test
    void returnsEmptyWhenScoreResultMissing() {
        assertThat(builder.buildTables("安徽630分", Map.of())).isEmpty();
    }
}
