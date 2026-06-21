package com.example.javaagentmvp.eval;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionCompilerProperties;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionOntologyRegistry;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPriorSlotsBuilder;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.IntentServiceClient;
import com.example.javaagentmvp.admissionworkflow.compiler.LocalAdmissionQueryCompiler;
import com.example.javaagentmvp.admissionworkflow.compiler.UnsupportedConstraintRecorder;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Golden intent cases via production path: {@link AdmissionQueryCompileService} → {@link AdmissionQueryIr#toIntent()}.
 */
class IntentClassifyEvalTest {

    private AdmissionQueryCompileService compileService;

    @BeforeEach
    void setUp() throws Exception {
        compileService = compileService();
    }

    @Test
    void runsGoldenIntentCases() throws Exception {
        List<EvalCaseLoader.IntentClassifyCase> cases = EvalCaseLoader.loadIntentCases(
                EvalCaseLoader.projectRoot().resolve("eval/cases/intent_classify.jsonl"));

        assertThat(cases).isNotEmpty();
        for (EvalCaseLoader.IntentClassifyCase evalCase : cases) {
            AdmissionQueryIr query = compileService.compile(evalCase.input());
            AdmissionIntent actual = query.toIntent();
            assertThat(actual.name())
                    .as("case %s input=%s task=%s", evalCase.id(), evalCase.input(), query.task())
                    .isEqualTo(evalCase.expectIntent());
        }
    }

    private static AdmissionQueryCompileService compileService() throws Exception {
        AdmissionOntologyRegistry ontologyRegistry = new AdmissionOntologyRegistry();
        ontologyRegistry.load();
        AdmissionPriorSlotsBuilder priorSlotsBuilder = new AdmissionPriorSlotsBuilder(ontologyRegistry);
        LocalAdmissionQueryCompiler localCompiler = new LocalAdmissionQueryCompiler(
                ontologyRegistry,
                priorSlotsBuilder,
                new RagQueryRouter(evalRagProperties()));
        return new AdmissionQueryCompileService(
                AdmissionCompilerProperties.defaults(),
                new IntentServiceClient(AdmissionCompilerProperties.defaults(), RestClient.builder()),
                localCompiler,
                priorSlotsBuilder);
    }

    private static RagProperties evalRagProperties() {
        return new RagProperties(
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
    }
}
