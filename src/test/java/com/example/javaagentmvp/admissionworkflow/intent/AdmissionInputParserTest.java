package com.example.javaagentmvp.admissionworkflow.intent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionInputParserTest {

    @Test
    void parsesRankProvinceAndSubjectForMajorSearch() {
        AdmissionInputParser.ParsedAdmissionInput parsed =
                AdmissionInputParser.parse("排名10000名 安徽 物理类 能报什么专业");

        assertThat(parsed.rank()).isEqualTo(10000);
        assertThat(parsed.score()).isNull();
        assertThat(parsed.province()).isEqualTo("安徽");
        assertThat(parsed.subjectGroup()).isEqualTo("物理类");
    }

    @Test
    void parseRank() {
        assertThat(AdmissionInputParser.parseRank("位次3286")).contains(3286);
        assertThat(AdmissionInputParser.parseRank("第5000名")).contains(5000);
        assertThat(AdmissionInputParser.parseRank("100000名")).contains(100000);
        assertThat(AdmissionInputParser.parseRank("100,000名")).contains(100000);
        assertThat(AdmissionInputParser.parseRank("10,0000名")).contains(100000);
        assertThat(AdmissionInputParser.parseRank("10万名")).contains(100000);
        assertThat(AdmissionInputParser.parseRank("排名10万名 安徽 物理类")).contains(100000);
        assertThat(AdmissionInputParser.parseRank("位次100,000")).contains(100000);
    }

    @Test
    void parsesFormattedRankForMajorSearch() {
        assertThat(AdmissionInputParser.parse("安徽 物理 100,000名 普通批 可以报考什么专业？").rank())
                .isEqualTo(100000);
        assertThat(AdmissionInputParser.parse("安徽 物理 10,0000名 普通批 可以报考什么专业？").rank())
                .isEqualTo(100000);
        assertThat(AdmissionInputParser.parse("安徽 物理 10万名 普通批 可以报考什么专业？").rank())
                .isEqualTo(100000);
    }

    @Test
    void parsesBareRankForMajorSearch() {
        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(
                "安徽 物理 100000名 普通批 可以报考什么学校专业？");

        assertThat(parsed.rank()).isEqualTo(100000);
        assertThat(parsed.score()).isNull();
        assertThat(parsed.province()).isEqualTo("安徽");
        assertThat(parsed.subjectGroup()).isEqualTo("物理类");
        assertThat(parsed.admissionType()).isEqualTo("普通批");
    }

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
