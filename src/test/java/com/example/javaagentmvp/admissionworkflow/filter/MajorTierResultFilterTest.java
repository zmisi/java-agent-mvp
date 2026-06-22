package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionFiltersIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MajorTierResultFilterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private static RagProperties hfutRegistry() {
    return new RagProperties(
        true,
        false,
        false,
        "agent_ui",
        "rag_vector_store",
        "",
        4,
        0.7,
        false,
        "",
        new RagProperties.Routing(List.of(), List.of()),
        new RagProperties.Admissions(
            true,
            List.of(),
            4,
            12,
            List.of(new RagProperties.School("hfut", "合肥工业大学", List.of("合工大"), List.of())),
            ""),
        new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
  }

  @Test
  void filtersTiersToRequestedSchool() throws Exception {
    String raw = objectMapper.writeValueAsString(Map.of(
        "majors_by_tier",
        Map.of(
            "冲",
            List.of(Map.of("university_name", "合肥工业大学", "major_name", "软件工程")),
            "稳",
            List.of(
                Map.of("university_name", "合肥工业大学", "major_name", "土木"),
                Map.of("university_name", "安徽大学", "major_name", "法学")),
            "保",
            List.of(Map.of("university_name", "安徽医科大学", "major_name", "临床"))),
        "tier_counts",
        Map.of("冲", 1, "稳", 2, "保", 1)));

    AdmissionQueryIr query = new AdmissionQueryIr(
        "search_majors",
        new AdmissionSlotsIr(631, null, List.of("安徽"), "物理类", null, null),
        new AdmissionFiltersIr(List.of(), List.of(), List.of(), List.of("合肥工业大学"), List.of(), List.of()),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.9,
        "631分 安徽 物理类 能报 合肥工业大学 什么专业",
        null);

    var filtered = MajorTierResultFilter.filter(objectMapper.readTree(raw), query, hfutRegistry(), objectMapper);

    assertThat(filtered.path("tier_counts").path("冲").asInt()).isEqualTo(1);
    assertThat(filtered.path("tier_counts").path("稳").asInt()).isEqualTo(1);
    assertThat(filtered.path("tier_counts").path("保").asInt()).isEqualTo(0);
    assertThat(filtered.path("majors_by_tier").path("稳").get(0).path("university_name").asText())
        .isEqualTo("合肥工业大学");
  }
}
