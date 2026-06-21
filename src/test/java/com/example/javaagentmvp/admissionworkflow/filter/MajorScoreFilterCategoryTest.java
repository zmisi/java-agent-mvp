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

class MajorScoreFilterCategoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void filtersByMajorDisciplineGroup() throws Exception {
        String raw = """
            {
              "count": 4,
              "majors": [
                {"university_name":"合肥工业大学","major_name":"计算机科学与技术","min_score":"620.00","discipline_category":"工学","discipline_groups":["工科"]},
                {"university_name":"合肥工业大学","major_name":"汉语言文学","min_score":"610.00","discipline_category":"文学","discipline_groups":["文科"]},
                {"university_name":"安徽大学","major_name":"软件工程","min_score":"618.00","discipline_category":"工学","discipline_groups":["工科"]},
                {"university_name":"安徽大学","major_name":"临床医学","min_score":"615.00","discipline_category":"医学","discipline_groups":["医科"]}
              ]
            }
            """;
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(620, null, List.of(), "物理类", null, null),
                new AdmissionFiltersIr(List.of(), List.of(), List.of(), List.of(), List.of("工科"), List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "只看工科专业",
                null);
        QueryConstraints constraints = QueryConstraints.fromIr(query, AdmissionQueryHints.parse("", ragProperties()));

        MajorScoreFilter.FilterResult result = MajorScoreFilter.filter(
                objectMapper.readTree(raw),
                620,
                new AdmissionQueryHints.Hints(List.of(), List.of(), false, false),
                constraints,
                objectMapper);

        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.majorFiltered()).isTrue();
        assertThat(result.payload().path("majors"))
                .extracting(node -> node.path("major_name").asText())
                .containsExactlyInAnyOrder("计算机科学与技术", "软件工程");
    }

    @Test
    void filtersByEconomicsDisciplineCategory() throws Exception {
        String raw = """
            {
              "count": 4,
              "majors": [
                {"university_name":"合肥工业大学","major_name":"经济学","min_score":"598.00","discipline_category":"经济学","discipline_groups":["社科"]},
                {"university_name":"合肥工业大学","major_name":"材料成型及控制工程","min_score":"600.00","discipline_category":"工学","discipline_groups":["工科"]},
                {"university_name":"合肥工业大学","major_name":"金融学","min_score":"599.00","discipline_category":"经济学","discipline_groups":["社科"]},
                {"university_name":"合肥工业大学","major_name":"物流管理（数智物流）","min_score":"597.00","discipline_category":"管理学","discipline_groups":["社科"]}
              ]
            }
            """;
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(600, null, List.of(), "物理类", null, null),
                new AdmissionFiltersIr(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("经济学")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "只看经济学",
                null);
        QueryConstraints constraints = QueryConstraints.fromIr(query, AdmissionQueryHints.parse("", ragProperties()));

        MajorScoreFilter.FilterResult result = MajorScoreFilter.filter(
                objectMapper.readTree(raw),
                600,
                new AdmissionQueryHints.Hints(List.of(), List.of(), false, false),
                constraints,
                objectMapper);

        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.payload().path("majors"))
                .extracting(node -> node.path("major_name").asText())
                .containsExactlyInAnyOrder("经济学", "金融学");
    }

    @Test
    void filtersEconomicsByMajorNameWhenCatalogMissing() throws Exception {
        String raw = """
            {
              "count": 3,
              "majors": [
                {"university_name":"合肥工业大学","major_name":"经济学","min_score":"598.00"},
                {"university_name":"合肥工业大学","major_name":"土木工程","min_score":"600.00"},
                {"university_name":"合肥工业大学","major_name":"国际经济与贸易","min_score":"599.00"}
              ]
            }
            """;
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(600, null, List.of(), "物理类", null, null),
                new AdmissionFiltersIr(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("经济学")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "只看经济学",
                null);
        QueryConstraints constraints = QueryConstraints.fromIr(query, AdmissionQueryHints.parse("", ragProperties()));

        MajorScoreFilter.FilterResult result = MajorScoreFilter.filter(
                objectMapper.readTree(raw),
                600,
                new AdmissionQueryHints.Hints(List.of(), List.of(), false, false),
                constraints,
                objectMapper);

        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.payload().path("majors"))
                .extracting(node -> node.path("major_name").asText())
                .containsExactlyInAnyOrder("经济学", "国际经济与贸易");
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
