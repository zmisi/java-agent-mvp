package com.example.javaagentmvp.admissionworkflow.engine;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

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
        when(workflowRunRepository.createRun(eq("admission_report"), eq(1L), eq(null), eq("hello")))
                .thenReturn("run-1");

        WorkflowEngine engine = new WorkflowEngine(
                workflowRunRepository, new AdmissionWorkflowProperties(true, "admission_report", false, null));

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
        when(workflowRunRepository.createRun(any(), any(), any(), any())).thenReturn("run-2");

        WorkflowEngine engine = new WorkflowEngine(
                workflowRunRepository, new AdmissionWorkflowProperties(true, "admission_report", false, null));
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
