package com.example.javaagentmvp.admissionworkflow.intent;

import com.example.javaagentmvp.rag.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionQueryHintsTest {

    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        ragProperties = new RagProperties(
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
                new RagProperties.Routing(List.of(), List.of()),
                new RagProperties.Admissions(
                        true,
                        List.of("招生章程", "政策"),
                        4,
                        12,
                        List.of(new RagProperties.School(
                                "hfut",
                                "合肥工业大学",
                                List.of("合工大", "HFUT"),
                                List.of("/hfut/"))),
                        ""),
                new RagProperties.Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }

    @Test
    void parsesHfutAndMajorKeywords() {
        AdmissionQueryHints.Hints hints = AdmissionQueryHints.parse(
                "安徽物理类630分，合工大计算机和软件工程政策", ragProperties);

        assertThat(hints.schoolSpecified()).isTrue();
        assertThat(hints.primarySchool()).isPresent();
        assertThat(hints.primarySchool().get().key()).isEqualTo("hfut");
        assertThat(hints.majorKeywords()).contains("计算机", "软件工程");
        assertThat(hints.policyRetrievalQuery("安徽物理类630分，合工大计算机和软件工程政策"))
                .contains("合肥工业大学")
                .contains("招生章程");
    }
}
