package com.example.javaagentmvp.admissionworkflow.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionScoreToolClientQueryScoreTest {

    @Test
    void mcpQueryScoreAddsTierDeltaForReachMajors() {
        assertThat(AdmissionScoreToolClient.mcpQueryScore(630)).isEqualTo(645);
    }

    @Test
    void mcpQueryScoreClampsToValidRange() {
        assertThat(AdmissionScoreToolClient.mcpQueryScore(740)).isEqualTo(750);
        assertThat(AdmissionScoreToolClient.mcpQueryScore(180)).isEqualTo(200);
    }
}
