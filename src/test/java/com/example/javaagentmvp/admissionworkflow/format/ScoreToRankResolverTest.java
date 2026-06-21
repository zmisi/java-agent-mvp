package com.example.javaagentmvp.admissionworkflow.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreToRankResolverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesRankFromMatchingYearAndSubject() throws Exception {
        String json = """
                {"count":2,"ranks":[
                  {"year":2024,"province":"安徽","subject_group":"物理类","rank_min":9000,"rank_max":9100},
                  {"year":2025,"province":"安徽","subject_group":"物理类","rank":8000,"rank_min":8000,"rank_max":8100}
                ]}""";
        assertThat(ScoreToRankResolver.resolveRank(objectMapper.readTree(json), 2025, "物理类"))
                .hasValue(8000);
    }

    @Test
    void fallsBackToLatestYearWhenRequestedYearMissing() throws Exception {
        String json = """
                {"count":1,"ranks":[
                  {"year":2025,"province":"安徽","subject_group":"物理类","rank_min":7000,"rank_max":7100}
                ]}""";
        assertThat(ScoreToRankResolver.resolveRank(objectMapper.readTree(json), 2024, "物理类"))
                .hasValue(7000);
    }
}
