package com.example.javaagentmvp.admissionworkflow.persistence.model;

import java.time.Instant;

public record WorkflowCheckpointRecord(
        Long id,
        String runId,
        String nodeName,
        int sequenceNo,
        String status,
        String inputJson,
        String outputJson,
        Instant startedAt,
        Instant finishedAt) {
}
