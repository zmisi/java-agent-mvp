package com.example.javaagentmvp.admissionworkflow.engine;

import java.util.Map;

public record WorkflowNodeResult(CheckpointStatus status, Map<String, Object> output) {

    public static WorkflowNodeResult succeeded(Map<String, Object> output) {
        return new WorkflowNodeResult(CheckpointStatus.SUCCEEDED, output == null ? Map.of() : output);
    }

    public static WorkflowNodeResult skipped(String reason) {
        return new WorkflowNodeResult(CheckpointStatus.SKIPPED, Map.of("reason", reason));
    }

    public static WorkflowNodeResult failed(String reason) {
        return new WorkflowNodeResult(CheckpointStatus.FAILED, Map.of("reason", reason));
    }

    public boolean isFailed() {
        return status == CheckpointStatus.FAILED;
    }
}
