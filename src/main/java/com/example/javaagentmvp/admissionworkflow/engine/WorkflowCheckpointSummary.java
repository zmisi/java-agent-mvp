package com.example.javaagentmvp.admissionworkflow.engine;

public record WorkflowCheckpointSummary(
        String node,
        CheckpointStatus status,
        long elapsedMs) {
}
