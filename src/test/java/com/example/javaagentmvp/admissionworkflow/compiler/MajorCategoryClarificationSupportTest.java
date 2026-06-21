package com.example.javaagentmvp.admissionworkflow.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MajorCategoryClarificationSupportTest {

    @Test
    void detectsDirectiveIntent() {
        assertThat(MajorCategoryClarificationSupport.expressesCategoryFilterIntent("只看法学专业"))
                .isTrue();
        assertThat(MajorCategoryClarificationSupport.expressesCategoryFilterIntent("600分安徽能上什么专业"))
                .isFalse();
    }

    @Test
    void needsClarificationWhenIntentUnresolved() {
        AdmissionOntologyRegistry.MajorCategoryMatch empty =
                new AdmissionOntologyRegistry.MajorCategoryMatch(List.of(), List.of(), List.of());

        assertThat(MajorCategoryClarificationSupport.needsMajorCategoryClarification(
                "只看法学专业", empty)).isTrue();
    }

    @Test
    void skipsClarificationWhenCategoryMatched() {
        AdmissionOntologyRegistry.MajorCategoryMatch matched =
                new AdmissionOntologyRegistry.MajorCategoryMatch(
                        List.of("工科"), List.of(), List.of("工科"));

        assertThat(MajorCategoryClarificationSupport.needsMajorCategoryClarification(
                "只看工科专业", matched)).isFalse();
    }

    @Test
    void skipsClarificationForSpecificMajorKeywords() {
        AdmissionOntologyRegistry.MajorCategoryMatch empty =
                new AdmissionOntologyRegistry.MajorCategoryMatch(List.of(), List.of(), List.of());

        assertThat(MajorCategoryClarificationSupport.needsMajorCategoryClarification(
                "只看计算机专业", empty)).isFalse();
    }
}
