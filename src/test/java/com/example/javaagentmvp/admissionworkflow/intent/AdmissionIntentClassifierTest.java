package com.example.javaagentmvp.admissionworkflow.intent;

import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionIntentClassifierTest {

    private AdmissionIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "classpath:/rag-docs/**/*.md",
                4,
                0.7,
                true,
                "",
                new RagProperties.Routing(List.of("招生简章|招生章程"), List.of("\\d{3,4}\\s*分")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策"),
                        4,
                        12,
                        List.of(),
                        ""),
                new RagProperties.Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
        classifier = new AdmissionIntentClassifier(new RagQueryRouter(properties), properties);
    }

    @Test
    void classifiesScoreIntent() {
        assertThat(classifier.classify("安徽物理类620分能上什么专业"))
                .isEqualTo(AdmissionIntent.SCORE);
    }

    @Test
    void classifiesPolicyIntent() {
        assertThat(classifier.classify("合工大2025年招生章程录取规则是什么"))
                .isEqualTo(AdmissionIntent.POLICY);
    }

    @Test
    void classifiesReportIntent() {
        assertThat(classifier.classify("安徽物理类620分，合工大计算机政策和能报哪些专业"))
                .isEqualTo(AdmissionIntent.REPORT);
    }
}
