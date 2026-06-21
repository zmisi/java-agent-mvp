package com.example.javaagentmvp.admissionworkflow.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FixedGuidanceSupportTest {

    @Test
    void messageIsNonBlank() {
        assertThat(FixedGuidanceSupport.MESSAGE).contains("冲/稳/保");
        assertThat(FixedGuidanceSupport.MESSAGE).contains("600分");
    }

    @Test
    void requiresFixedGuidanceForUnknownOffTopic() {
        AdmissionQueryIr query = new AdmissionQueryIr(
                "unknown",
                AdmissionSlotsIr.empty(),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.35,
                "哈哈",
                null);

        assertThat(FixedGuidanceSupport.requiresFixedGuidance(query)).isTrue();
    }

    @Test
    void doesNotRequireFixedGuidanceWhenClarificationPending() {
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(600, null, List.of(), null, null, null),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of("provinces", "subject_group"),
                0.72,
                "600分",
                null);

        assertThat(FixedGuidanceSupport.requiresFixedGuidance(query)).isFalse();
    }
}
