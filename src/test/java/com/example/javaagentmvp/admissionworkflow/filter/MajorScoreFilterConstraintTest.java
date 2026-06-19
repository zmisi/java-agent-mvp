package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionFiltersIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MajorScoreFilterConstraintTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void excludesNormalUniversityAndFiltersProvinces() throws Exception {
        String raw = """
            {
              "count": 4,
              "majors": [
                {"university_name":"南京师范大学","major_name":"汉语言文学","min_score":"610.00","query_province":"江苏"},
                {"university_name":"南京大学","major_name":"计算机科学与技术","min_score":"620.00","query_province":"江苏"},
                {"university_name":"浙江大学","major_name":"软件工程","min_score":"618.00","query_province":"浙江"},
                {"university_name":"复旦大学","major_name":"教育学","min_score":"615.00","query_province":"上海"}
              ]
            }
            """;
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(620, List.of("江苏", "浙江", "上海"), "物理类", null, null),
                new AdmissionFiltersIr(List.of("师范"), List.of("师范", "教育学"), List.of(), List.of()),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "test",
                null);
        QueryConstraints constraints = QueryConstraints.fromIr(query, AdmissionQueryHints.parse("", ragProperties()));

        MajorScoreFilter.FilterResult result = MajorScoreFilter.filter(
                objectMapper.readTree(raw),
                620,
                new AdmissionQueryHints.Hints(List.of(), List.of(), false, false),
                constraints,
                objectMapper);

        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.payload().path("majors"))
                .extracting(node -> node.path("major_name").asText())
                .containsExactlyInAnyOrder("计算机科学与技术", "软件工程");
    }

    private static RagProperties ragProperties() {
        return new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "classpath:/rag-docs/**/*.md",
                4,
                0.7,
                true,
                "",
                new RagProperties.Routing(List.of(), List.of()),
                new RagProperties.Admissions(true, List.of(), 4, 12, List.of(), ""),
                new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }
}
