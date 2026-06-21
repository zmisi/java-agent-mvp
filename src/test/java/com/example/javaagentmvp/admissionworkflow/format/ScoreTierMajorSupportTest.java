package com.example.javaagentmvp.admissionworkflow.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreTierMajorSupportTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void isSparseRankResultDetectsUnderfilledTiers() throws Exception {
        String sparse = """
                {"tier_counts":{"冲":0,"稳":1,"保":22},"majors_by_tier":{"冲":[],"稳":[{}],"保":[{}]}}
                """;
        String rich = """
                {"tier_counts":{"冲":24,"稳":65,"保":911}}
                """;

        assertThat(ScoreTierMajorSupport.isSparseRankResult(objectMapper.readTree(sparse))).isTrue();
        assertThat(ScoreTierMajorSupport.isSparseRankResult(objectMapper.readTree(rich))).isFalse();
    }

    @Test
    void buildTieredResponseClassifiesByScoreDelta() throws Exception {
        String majors = """
                {"majors":[
                  {"major_name":"冲高","min_score":"640"},
                  {"major_name":"稳妥","min_score":"625"},
                  {"major_name":"保底","min_score":"610"}
                ]}
                """;

        var tiered = ScoreTierMajorSupport.buildTieredResponse(
                630,
                objectMapper.readTree(majors).get("majors"),
                objectMapper,
                9967);

        assertThat(tiered.path("tier_classification").asText()).isEqualTo("score");
        assertThat(tiered.path("resolved_rank").asInt()).isEqualTo(9967);
        assertThat(tiered.path("tier_counts").path("冲").asInt()).isEqualTo(1);
        assertThat(tiered.path("tier_counts").path("稳").asInt()).isEqualTo(1);
        assertThat(tiered.path("tier_counts").path("保").asInt()).isEqualTo(1);
    }
}
