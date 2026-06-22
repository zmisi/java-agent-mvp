package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.rag.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncludeSchoolSupportTest {

  private static RagProperties schoolsRegistry() {
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
            List.of(
                new RagProperties.School(
                    "hfut",
                    "合肥工业大学",
                    List.of("合工大", "合肥工业大学", "HFUT"),
                    List.of()),
                new RagProperties.School(
                    "ahu",
                    "安徽大学",
                    List.of("安大", "安徽大学", "AHU"),
                    List.of())),
            ""),
        new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
  }

  @Test
  void matchesExplicitUniversityName() {
    List<String> schools = IncludeSchoolSupport.matchIncludeSchools(
        "631分 安徽 物理类 能报 合肥工业大学 什么专业",
        schoolsRegistry());

    assertThat(schools).containsExactly("合肥工业大学");
  }

  @Test
  void doesNotMatchProvinceAlone() {
    List<String> schools = IncludeSchoolSupport.matchIncludeSchools(
        "631分 安徽 物理类 能报什么专业",
        schoolsRegistry());

    assertThat(schools).isEmpty();
  }

  @Test
  void rejectsHallucinatedIncludeSchoolNotInMessage() {
    AdmissionQueryIr query = new AdmissionQueryIr(
        "search_majors",
        AdmissionSlotsIr.empty(),
        new AdmissionFiltersIr(
            List.of(),
            List.of(),
            List.of(),
            List.of("安徽大学"),
            List.of(),
            List.of()),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.9,
        "631分 安徽 物理类 能报 合肥工业大学 什么专业",
        null);

    assertThat(IncludeSchoolSupport.effectiveIncludeSchools(query, schoolsRegistry()))
        .containsExactly("合肥工业大学");
  }
}
