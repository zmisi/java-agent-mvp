package com.example.javaagentmvp.admissionworkflow.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionRankQueryTest {

    @Test
    void detectsRankOnlyQueries() {
        assertThat(AdmissionRankQuery.isRankQuery("安徽物理类620分排名多少")).isTrue();
        assertThat(AdmissionRankQuery.isRankQuery("630分对应什么位次")).isTrue();
    }

    @Test
    void rejectsMajorQueriesEvenWithRankKeywordInDocs() {
        assertThat(AdmissionRankQuery.isRankQuery("安徽物理类620分能上什么专业")).isFalse();
        assertThat(AdmissionRankQuery.isRankQuery("620分排名和专业")).isFalse();
    }
}
