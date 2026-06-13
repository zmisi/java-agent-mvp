package com.example.javaagentmvp.admissionworkflow.intent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionInputParserTest {

    @Test
    void parsesScoreProvinceAndSubject() {
        AdmissionInputParser.ParsedAdmissionInput parsed =
                AdmissionInputParser.parse("安徽物理类620分能上什么专业");

        assertThat(parsed.score()).isEqualTo(620);
        assertThat(parsed.province()).isEqualTo("安徽");
        assertThat(parsed.subjectGroup()).isEqualTo("物理类");
    }

    @Test
    void parsesScoreWhenYearAppearsBeforeScore() {
        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(
                "安徽 2025 物理 普通批 630 可以报考什么专业？");

        assertThat(parsed.score()).isEqualTo(630);
        assertThat(parsed.year()).isEqualTo(2025);
        assertThat(parsed.province()).isEqualTo("安徽");
        assertThat(parsed.subjectGroup()).isEqualTo("物理类");
        assertThat(parsed.admissionType()).isEqualTo("普通批");
    }

    @Test
    void detectsMissingProvince() {
        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse("620分能上什么专业");
        assertThat(AdmissionInputParser.describeMissingFields(parsed)).isEqualTo("province");
    }

    @Test
    void parseYear() {
        Optional<Integer> year = AdmissionInputParser.parseYear("2025年安徽620分");
        assertThat(year).contains(2025);
    }

    @Test
    void parseScorePrefersExplicitFenSuffix() {
        assertThat(AdmissionInputParser.parseScore("2025年安徽630分")).contains(630);
    }
}
