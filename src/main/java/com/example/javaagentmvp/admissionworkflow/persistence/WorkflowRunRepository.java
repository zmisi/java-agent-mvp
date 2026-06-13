package com.example.javaagentmvp.admissionworkflow.persistence;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowRunStatus;
import com.example.javaagentmvp.admissionworkflow.persistence.mapper.WorkflowCheckpointMapper;
import com.example.javaagentmvp.admissionworkflow.persistence.mapper.WorkflowRunMapper;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowCheckpointRecord;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunRecord;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WorkflowRunRepository {

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowCheckpointMapper workflowCheckpointMapper;
    private final ObjectMapper objectMapper;

    public WorkflowRunRepository(
            WorkflowRunMapper workflowRunMapper,
            WorkflowCheckpointMapper workflowCheckpointMapper,
            ObjectMapper objectMapper) {
        this.workflowRunMapper = workflowRunMapper;
        this.workflowCheckpointMapper = workflowCheckpointMapper;
        this.objectMapper = objectMapper;
    }

    public String createRun(
            String workflowType,
            Long userId,
            String conversationId,
            String inputMessage) {
        Instant now = Instant.now();
        String runId = UUID.randomUUID().toString();
        workflowRunMapper.insert(new WorkflowRunRecord(
                runId,
                workflowType,
                WorkflowRunStatus.RUNNING.name(),
                userId,
                conversationId,
                inputMessage,
                null,
                null,
                now,
                now,
                null));
        return runId;
    }

    public void markFailed(String runId, String errorMessage) {
        Instant now = Instant.now();
        workflowRunMapper.updateStatus(
                runId, WorkflowRunStatus.FAILED.name(), now, now, errorMessage);
    }

    public void markSucceeded(String runId, Map<String, Object> result) {
        Instant now = Instant.now();
        workflowRunMapper.updateResult(
                runId,
                WorkflowRunStatus.SUCCEEDED.name(),
                toJson(result),
                now,
                now);
    }

    public void insertCheckpoint(
            String runId,
            String nodeName,
            int sequenceNo,
            String status,
            Map<String, Object> input,
            Map<String, Object> output,
            Instant startedAt,
            Instant finishedAt) {
        workflowCheckpointMapper.insert(new WorkflowCheckpointRecord(
                null,
                runId,
                nodeName,
                sequenceNo,
                status,
                toJson(input),
                toJson(output),
                startedAt,
                finishedAt));
    }

    public Optional<WorkflowRunSummaryRow> findSummary(String runId) {
        return Optional.ofNullable(workflowRunMapper.selectSummaryById(runId));
    }

    public List<WorkflowCheckpointRecord> listCheckpoints(String runId) {
        return workflowCheckpointMapper.listByRunId(runId);
    }

    public boolean existsForUser(String runId, long userId) {
        return workflowRunMapper.countByIdAndUserId(runId, userId) > 0;
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize workflow JSON", ex);
        }
    }
}
