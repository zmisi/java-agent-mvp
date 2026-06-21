package com.example.javaagentmvp.eval;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.AsyncProperties;
import com.example.javaagentmvp.admissionworkflow.SynthesisProperties;
import com.example.javaagentmvp.admissionworkflow.engine.CheckpointStatus;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowDefinition;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowEngine;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowExecutionResult;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowRunStatus;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionCompilerProperties;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionOntologyRegistry;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPriorSlotsBuilder;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.IntentServiceClient;
import com.example.javaagentmvp.admissionworkflow.compiler.LocalAdmissionQueryCompiler;
import com.example.javaagentmvp.admissionworkflow.compiler.UnsupportedConstraintRecorder;
import com.example.javaagentmvp.admissionworkflow.nodes.CompileQueryNode;
import com.example.javaagentmvp.admissionworkflow.nodes.FilterScoreMajorsNode;
import com.example.javaagentmvp.admissionworkflow.nodes.FormatResponseNode;
import com.example.javaagentmvp.admissionworkflow.nodes.PolicyRagNode;
import com.example.javaagentmvp.admissionworkflow.nodes.PreferenceRagNode;
import com.example.javaagentmvp.admissionworkflow.nodes.ScoreToolNode;
import com.example.javaagentmvp.admissionworkflow.nodes.VerifyAnswerNode;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import com.example.javaagentmvp.admissionworkflow.tool.AdmissionScoreToolClient;
import com.example.javaagentmvp.chat.ConversationPriorUserMessagesResolver;
import com.example.javaagentmvp.chat.PostgresChatMemory;
import com.example.javaagentmvp.observability.AgentMetrics;
import com.example.javaagentmvp.observability.WorkflowTraceSupport;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import com.example.javaagentmvp.rag.RagRetrievalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("eval")
class WorkflowDeterministicEvalTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private AdmissionScoreToolClient admissionScoreToolClient;

    @Mock
    private ObjectProvider<RagRetrievalService> ragRetrievalServiceProvider;

    @Mock
    private RagRetrievalService ragRetrievalService;

    private WorkflowEngine workflowEngine;

    @BeforeEach
    void setUp() {
        WorkflowTraceSupport traceSupport = new WorkflowTraceSupport(ObservationRegistry.create());
        workflowEngine = new WorkflowEngine(
                workflowRunRepository,
                evalProperties(),
                new AgentMetrics(new SimpleMeterRegistry()),
                traceSupport);

        when(ragRetrievalServiceProvider.getIfAvailable()).thenReturn(ragRetrievalService);
        when(ragRetrievalService.search(anyString(), any(), any())).thenReturn(List.of(
                new Document("招生章程内容", Map.of(
                        "title", "合工大章程",
                        "source", "rag-docs/hfut/charters/2025/章程.md",
                        "school", "合肥工业大学"))));
    }

    @Test
    void runsDeterministicWorkflowCases() throws Exception {
        List<EvalCaseLoader.WorkflowEvalCase> cases = EvalCaseLoader.loadWorkflowCases(
                EvalCaseLoader.projectRoot().resolve("eval/cases/workflow_deterministic.jsonl"));
        assertThat(cases).isNotEmpty();

        for (EvalCaseLoader.WorkflowEvalCase evalCase : cases) {
            runCase(evalCase);
        }
    }

    private void runCase(EvalCaseLoader.WorkflowEvalCase evalCase) throws Exception {
        String runId = "eval-" + evalCase.id();
        when(workflowRunRepository.createPendingRun(any(), any(), any(), eq(evalCase.input()))).thenReturn(runId);
        when(workflowRunRepository.findSummary(runId)).thenReturn(Optional.of(runRow(runId, evalCase.input())));

        if (evalCase.requireScoreResult()) {
            ObjectNode scoreFixture = new ObjectMapper().createObjectNode();
            scoreFixture.put("count", 2);
            scoreFixture.put("user_rank", 8000);
            scoreFixture.put("user_score", 620);
            ObjectNode byTier = scoreFixture.putObject("majors_by_tier");
            byTier.putArray("冲");
            byTier.putArray("稳");
            byTier.putArray("保");
            scoreFixture.putArray("majors")
                    .addObject()
                    .put("major_name", "计算机科学与技术")
                    .put("min_score", 610)
                    .put("university", "合肥工业大学");
            when(admissionScoreToolClient.getMajorsForScore(
                    eq(runId), anyInt(), anyString(), any(), any(), any()))
                    .thenReturn(scoreFixture);
        }

        long startedAt = System.currentTimeMillis();
        WorkflowExecutionResult result = workflowEngine.executeSync(
                workflowDefinition(),
                1L,
                null,
                evalCase.input());
        long elapsedMs = System.currentTimeMillis() - startedAt;

        assertThat(result.status().name())
                .as("case %s status", evalCase.id())
                .isEqualTo(evalCase.expectStatus());
        assertThat(elapsedMs)
                .as("case %s latency", evalCase.id())
                .isLessThan(evalCase.maxLatencyMs());

        if (evalCase.expectIntent() != null) {
            assertThat(result.result().get("intent"))
                    .as("case %s intent", evalCase.id())
                    .isEqualTo(evalCase.expectIntent());
        }

        for (String expectedNode : evalCase.expectNodesExecuted()) {
            assertThat(result.checkpoints().stream().map(cp -> cp.node()).toList())
                    .as("case %s missing node %s", evalCase.id(), expectedNode)
                    .contains(expectedNode);
        }

        if (evalCase.requireScoreResult()) {
            assertThat(result.result()).containsKey("scoreResult");
        }
        if (evalCase.requirePolicySources()) {
            assertThat(result.result()).containsKey("policySources");
        }
        if (evalCase.requireClarification()) {
            assertThat(result.result().get("needsClarification"))
                    .as("case %s clarification", evalCase.id())
                    .isEqualTo(true);
        }
    }

    private WorkflowDefinition workflowDefinition() throws Exception {
        RagProperties ragProperties = ragProperties();
        AdmissionOntologyRegistry ontologyRegistry = new AdmissionOntologyRegistry();
        ontologyRegistry.load();
        LocalAdmissionQueryCompiler localCompiler = new LocalAdmissionQueryCompiler(
                ontologyRegistry,
                new AdmissionPriorSlotsBuilder(ontologyRegistry),
                new RagQueryRouter(ragProperties));
        AdmissionQueryCompileService compileService = new AdmissionQueryCompileService(
                AdmissionCompilerProperties.defaults(),
                new IntentServiceClient(AdmissionCompilerProperties.defaults(), org.springframework.web.client.RestClient.builder()),
                localCompiler,
                new AdmissionPriorSlotsBuilder(ontologyRegistry));
        ObjectMapper objectMapper = new ObjectMapper();
        PostgresChatMemory chatMemory = mock(PostgresChatMemory.class);
        ConversationPriorUserMessagesResolver priorUserMessagesResolver =
                new ConversationPriorUserMessagesResolver(chatMemory);
        @SuppressWarnings("unchecked")
        ObjectProvider<UnsupportedConstraintRecorder> recorderProvider = mock(ObjectProvider.class);
        return new WorkflowDefinition(
                "admission_report",
                List.of(
                        new CompileQueryNode(compileService, priorUserMessagesResolver, recorderProvider),
                        new ScoreToolNode(admissionScoreToolClient, objectMapper),
                        new PreferenceRagNode(ragRetrievalServiceProvider),
                        new FilterScoreMajorsNode(ragProperties, objectMapper),
                        new PolicyRagNode(ragRetrievalServiceProvider, ragProperties),
                        new VerifyAnswerNode(),
                        new FormatResponseNode()));
    }

    private static AdmissionWorkflowProperties evalProperties() {
        return new AdmissionWorkflowProperties(
                true,
                "admission_report",
                false,
                SynthesisProperties.defaults(),
                AsyncProperties.defaults());
    }

    private static RagProperties ragProperties() {
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
                new RagProperties.Routing(List.of("招生简章|招生章程"), List.of("\\d{3,4}\\s*分")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策", "招生计划"),
                        4,
                        12,
                        List.of(),
                        ""),
                new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }

    private static WorkflowRunSummaryRow runRow(String runId, String message) {
        Instant now = Instant.now();
        return new WorkflowRunSummaryRow(
                runId,
                "admission_report",
                WorkflowRunStatus.RUNNING.name(),
                1L,
                null,
                message,
                null,
                null,
                now,
                now,
                null,
                0);
    }
}
