package com.example.javaagentmvp.admissionworkflow.persistence.model;

import java.time.Instant;

public record WorkflowRunSummaryRow(
        String id,
        String workflowType,
        String status,
        Long userId,
        String conversationId,
        String inputMessage,
        String resultJson,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt,
        int checkpointCount) {
}
