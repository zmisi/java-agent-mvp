package com.example.javaagentmvp.admissionworkflow.engine;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.AsyncProperties;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Test
    void executesNodesInOrderAndPersistsCheckpoints() {
        when(workflowRunRepository.createPendingRun(eq("admission_report"), eq(1L), eq(null), eq("hello")))
                .thenReturn("run-1");
        when(workflowRunRepository.findSummary("run-1")).thenReturn(Optional.of(runRow("run-1", "hello")));

        WorkflowEngine engine = engine();

        WorkflowNode first = new StubNode("intent_classify", WorkflowNodeResult.succeeded(Map.of("intent", "SCORE")));
        WorkflowNode second = new StubNode("format_response", context -> {
            context.put("finalResult", Map.of("summary", "done"));
            return WorkflowNodeResult.succeeded(Map.of("summary", "done"));
        });

        WorkflowExecutionResult result = engine.execute(
                new WorkflowDefinition("admission_report", List.of(first, second)),
                1L,
                null,
                "hello");

        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        assertThat(result.result()).containsEntry("summary", "done");
        assertThat(result.checkpoints()).extracting(WorkflowCheckpointSummary::node)
                .containsExactly("intent_classify", "format_response");

        verify(workflowRunRepository).markRunning("run-1");
        verify(workflowRunRepository).markSucceeded(eq("run-1"), eq(Map.of("summary", "done")));
        verify(workflowRunRepository).insertCheckpoint(
                eq("run-1"),
                eq("intent_classify"),
                eq(1),
                eq("SUCCEEDED"),
                any(),
                any(),
                any(),
                any());
    }

    @Test
    void stopsOnFailedNode() {
        when(workflowRunRepository.createPendingRun(any(), any(), any(), any())).thenReturn("run-2");
        when(workflowRunRepository.findSummary("run-2")).thenReturn(Optional.of(runRow("run-2", "620分")));

        WorkflowEngine engine = engine();
        WorkflowNode failing = new StubNode("score_tool", WorkflowNodeResult.failed("tool unavailable"));

        WorkflowExecutionResult result = engine.execute(
                new WorkflowDefinition("admission_report", List.of(failing)),
                2L,
                null,
                "620分");

        assertThat(result.status()).isEqualTo(WorkflowRunStatus.FAILED);
        assertThat(result.errorMessage()).contains("tool unavailable");
        verify(workflowRunRepository).markFailed(eq("run-2"), eq("score_tool: tool unavailable"));
    }

    @Test
    void enqueueCreatesPendingRunWithoutExecuting() {
        when(workflowRunRepository.createPendingRun(eq("admission_report"), eq(3L), eq("conv"), eq("msg")))
                .thenReturn("run-3");

        WorkflowEngine engine = engine();
        String runId = engine.enqueue(
                new WorkflowDefinition("admission_report", List.of()),
                3L,
                "conv",
                "msg",
                queued -> assertThat(queued).isEqualTo("run-3"));

        assertThat(runId).isEqualTo("run-3");
    }

    @Test
    void executeExistingRunsFromPendingRun() {
        when(workflowRunRepository.findSummary("run-4")).thenReturn(Optional.of(runRow("run-4", "安徽620")));

        WorkflowEngine engine = engine();
        WorkflowNode node = new StubNode("format_response", context -> {
            context.put("finalResult", Map.of("summary", "ok"));
            return WorkflowNodeResult.succeeded(Map.of("summary", "ok"));
        });

        WorkflowExecutionResult result = engine.executeExisting(
                new WorkflowDefinition("admission_report", List.of(node)),
                "run-4");

        assertThat(result.status()).isEqualTo(WorkflowRunStatus.SUCCEEDED);
        verify(workflowRunRepository).markSucceeded(eq("run-4"), eq(Map.of("summary", "ok")));
    }

    private WorkflowEngine engine() {
        return new WorkflowEngine(
                workflowRunRepository,
                new AdmissionWorkflowProperties(
                        true,
                        "admission_report",
                        false,
                        null,
                        AsyncProperties.defaults()),
                new com.example.javaagentmvp.observability.AgentMetrics(new SimpleMeterRegistry()),
                new com.example.javaagentmvp.observability.WorkflowTraceSupport(ObservationRegistry.create()));
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

    private static final class StubNode implements WorkflowNode {

        private final String name;
        private final java.util.function.Function<WorkflowContext, WorkflowNodeResult> executor;

        private StubNode(String name, WorkflowNodeResult result) {
            this(name, context -> result);
        }

        private StubNode(String name, java.util.function.Function<WorkflowContext, WorkflowNodeResult> executor) {
            this.name = name;
            this.executor = executor;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowContext context) {
            return executor.apply(context);
        }
    }
}
