package com.example.javaagentmvp.admissionworkflow.policy;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicySourceSelectorTest {

    @Test
    void dedupesSourcesByPathBeforeLimit() {
        List<RagSource> sources = List.of(
                new RagSource("章程 A", "hfut/2025/charter.md", "snippet 1", "hfut"),
                new RagSource("章程 B", "hfut/2025/charter.md", "snippet 2", "hfut"),
                new RagSource("计划", "hfut/2025/plan.md", "snippet 3", "hfut"));

        AdmissionQueryHints.Hints hints = new AdmissionQueryHints.Hints(
                List.of(new RagProperties.School("hfut", "合肥工业大学", List.of(), List.of("hfut"))),
                List.of("计算机"),
                true,
                true);

        List<RagSource> refined = PolicySourceSelector.refine(sources, hints);

        assertThat(refined).hasSize(2);
        assertThat(refined.stream().map(RagSource::source)).doesNotHaveDuplicates();
    }
}
