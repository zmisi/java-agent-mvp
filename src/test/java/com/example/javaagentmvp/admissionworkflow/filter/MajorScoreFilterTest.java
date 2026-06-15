package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MajorScoreFilterTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void filtersBySchoolAndMajorAndBuildsTiers() throws Exception {
    String raw = """
        {
          "count": 3,
          "majors": [
            {"university_code":"hfut","university_name":"合肥工业大学","major_name":"软件工程","min_score":"622.00"},
            {"university_code":"hfut","university_name":"合肥工业大学","major_name":"土木工程","min_score":"610.00"},
            {"university_code":"ahu","university_name":"安徽大学","major_name":"软件工程","min_score":"620.00"}
          ]
        }
        """;
    AdmissionQueryHints.Hints hints = new AdmissionQueryHints.Hints(
        List.of(new RagProperties.School("hfut", "合肥工业大学", List.of("合工大"), List.of("/hfut/"))),
        List.of("软件工程"),
        true,
        true);

    MajorScoreFilter.FilterResult result =
        MajorScoreFilter.filter(objectMapper.readTree(raw), 630, hints, objectMapper);

    assertThat(result.matchedCount()).isEqualTo(1);
    assertThat(result.payload().path("majors").get(0).path("major_name").asText()).isEqualTo("软件工程");
    assertThat(result.tierCounts().get("稳")).isEqualTo(1);
  }

  @Test
  void classifiesReachSteadyAndSafeTiersAroundUserScore() throws Exception {
    String raw = """
        {
          "count": 4,
          "majors": [
            {"university_code":"hfut","university_name":"合肥工业大学","major_name":"计算机科学与技术","min_score":"638.00"},
            {"university_code":"hfut","university_name":"合肥工业大学","major_name":"软件工程","min_score":"622.00"},
            {"university_code":"hfut","university_name":"合肥工业大学","major_name":"土木工程","min_score":"610.00"},
            {"university_code":"hfut","university_name":"合肥工业大学","major_name":"机械工程","min_score":"600.00"}
          ]
        }
        """;
    AdmissionQueryHints.Hints hints = new AdmissionQueryHints.Hints(
        List.of(new RagProperties.School("hfut", "合肥工业大学", List.of("合工大"), List.of("/hfut/"))),
        List.of("计算机", "软件工程", "土木", "机械"),
        true,
        true);

    MajorScoreFilter.FilterResult result =
        MajorScoreFilter.filter(objectMapper.readTree(raw), 630, hints, objectMapper);

    assertThat(result.tierCounts().get("冲")).isEqualTo(1);
    assertThat(result.tierCounts().get("稳")).isEqualTo(1);
    assertThat(result.tierCounts().get("保")).isEqualTo(2);
    assertThat(result.payload().path("majors_by_tier").path("冲").get(0).path("major_name").asText())
        .isEqualTo("计算机科学与技术");
  }
}
