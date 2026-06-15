package com.example.javaagentmvp.eval;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntentClassifier;
import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassifyEvalTest {

    @Test
    void runsGoldenIntentCases() throws Exception {
        AdmissionIntentClassifier classifier = classifier();
        List<EvalCaseLoader.IntentClassifyCase> cases = EvalCaseLoader.loadIntentCases(
                EvalCaseLoader.projectRoot().resolve("eval/cases/intent_classify.jsonl"));

        assertThat(cases).isNotEmpty();
        for (EvalCaseLoader.IntentClassifyCase evalCase : cases) {
            AdmissionIntent actual = classifier.classify(evalCase.input());
            assertThat(actual.name())
                    .as("case %s input=%s", evalCase.id(), evalCase.input())
                    .isEqualTo(evalCase.expectIntent());
        }
    }

    private static AdmissionIntentClassifier classifier() {
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
                new RagProperties.Routing(
                        List.of("招生简章|招生章程", "\\brag\\b", "微调|知识库"),
                        List.of("\\d{3,4}\\s*分")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策", "招生计划"),
                        4,
                        12,
                        List.of(),
                        ""),
                new RagProperties.Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
        return new AdmissionIntentClassifier(
                new RagQueryRouter(properties, new ConversationTurnResolver()),
                properties);
    }
}
