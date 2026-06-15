package com.example.javaagentmvp.admissionworkflow.engine;

import java.util.List;
import java.util.Map;

public record WorkflowExecutionResult(
        String runId,
        WorkflowRunStatus status,
        Map<String, Object> result,
        String errorMessage,
        List<WorkflowCheckpointSummary> checkpoints) {
}
