package com.example.javaagentmvp.admissionworkflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAdmissionYearTest {

    @Test
    void resolvesExplicitYear() {
        assertThat(DefaultAdmissionYear.resolve(2024)).isEqualTo(2024);
    }

    @Test
    void defaultsWhenYearOmitted() {
        assertThat(DefaultAdmissionYear.resolve(null)).isEqualTo(DefaultAdmissionYear.VALUE);
        assertThat(DefaultAdmissionYear.VALUE).isEqualTo(2025);
    }
}
