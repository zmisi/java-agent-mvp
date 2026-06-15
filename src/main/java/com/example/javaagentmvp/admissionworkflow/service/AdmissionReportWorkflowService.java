package com.example.javaagentmvp.admissionworkflow.service;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.async.WorkflowJobQueue;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowDefinition;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowEngine;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowExecutionResult;
import com.example.javaagentmvp.admissionworkflow.nodes.FilterScoreMajorsNode;
import com.example.javaagentmvp.admissionworkflow.nodes.FormatResponseNode;
import com.example.javaagentmvp.admissionworkflow.nodes.IntentClassifyNode;
import com.example.javaagentmvp.admissionworkflow.nodes.PolicyRagNode;
import com.example.javaagentmvp.admissionworkflow.nodes.ScoreToolNode;
import com.example.javaagentmvp.admissionworkflow.nodes.SynthesizeReportNode;
import com.example.javaagentmvp.admissionworkflow.nodes.VerifyAnswerNode;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowCheckpointRecord;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "app.admission-workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionReportWorkflowService {

    private final WorkflowEngine workflowEngine;
    private final AdmissionWorkflowProperties properties;
    private final WorkflowRunRepository workflowRunRepository;
    private final ObjectMapper objectMapper;
    private final WorkflowDefinition admissionReportDefinition;
    private final WorkflowJobQueue workflowJobQueue;

    public AdmissionReportWorkflowService(
            WorkflowEngine workflowEngine,
            AdmissionWorkflowProperties properties,
            WorkflowRunRepository workflowRunRepository,
            ObjectMapper objectMapper,
            IntentClassifyNode intentClassifyNode,
            ScoreToolNode scoreToolNode,
            FilterScoreMajorsNode filterScoreMajorsNode,
            PolicyRagNode policyRagNode,
            VerifyAnswerNode verifyAnswerNode,
            FormatResponseNode formatResponseNode,
            SynthesizeReportNode synthesizeReportNode,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            WorkflowJobQueue workflowJobQueue) {
        this.workflowEngine = workflowEngine;
        this.properties = properties;
        this.workflowRunRepository = workflowRunRepository;
        this.objectMapper = objectMapper;
        this.workflowJobQueue = workflowJobQueue;
        this.admissionReportDefinition = new WorkflowDefinition(
                properties.defaultWorkflowType(),
                List.of(
                        intentClassifyNode,
                        scoreToolNode,
                        filterScoreMajorsNode,
                        policyRagNode,
                        verifyAnswerNode,
                        formatResponseNode,
                        synthesizeReportNode));
    }

    public WorkflowExecutionResult runReport(Long userId, String conversationId, String message) {
        return runReportSync(userId, conversationId, message);
    }

    public WorkflowExecutionResult runReportSync(Long userId, String conversationId, String message) {
        return workflowEngine.executeSync(admissionReportDefinition, userId, conversationId, message);
    }

    public String enqueueReport(Long userId, String conversationId, String message) {
        if (workflowJobQueue == null) {
            throw new IllegalStateException("async workflow queue is not enabled");
        }
        return workflowEngine.enqueue(
                admissionReportDefinition,
                userId,
                conversationId,
                message,
                workflowJobQueue::enqueue);
    }

    public WorkflowExecutionResult runExisting(String runId) {
        return workflowEngine.executeExisting(admissionReportDefinition, runId);
    }

    public boolean tryMarkRunning(String runId) {
        return workflowRunRepository.tryMarkRunning(runId);
    }

    public int totalNodeCount() {
        return admissionReportDefinition.nodes().size();
    }

    public Optional<WorkflowRunView> findRun(String runId) {
        return workflowRunRepository.findSummary(runId).map(this::toRunView);
    }

    public List<WorkflowCheckpointView> listCheckpoints(String runId) {
        return workflowRunRepository.listCheckpoints(runId).stream()
                .map(this::toCheckpointView)
                .toList();
    }

    private WorkflowRunView toRunView(WorkflowRunSummaryRow row) {
        return new WorkflowRunView(
                row.id(),
                row.workflowType(),
                row.status(),
                row.conversationId(),
                row.inputMessage(),
                parseJsonMap(row.resultJson()),
                row.errorMessage(),
                row.createdAt(),
                row.updatedAt(),
                row.finishedAt(),
                row.checkpointCount());
    }

    private WorkflowCheckpointView toCheckpointView(WorkflowCheckpointRecord record) {
        long elapsedMs = record.finishedAt().toEpochMilli() - record.startedAt().toEpochMilli();
        return new WorkflowCheckpointView(
                record.nodeName(),
                record.status(),
                elapsedMs,
                parseJsonMap(record.inputJson()),
                parseJsonMap(record.outputJson()),
                record.startedAt(),
                record.finishedAt());
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        }
        catch (JsonProcessingException ex) {
            return Map.of("raw", json);
        }
    }

    public record WorkflowRunView(
            String runId,
            String workflowType,
            String status,
            String conversationId,
            String inputMessage,
            Map<String, Object> result,
            String errorMessage,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant finishedAt,
            int checkpointCount) {
    }

    public record WorkflowCheckpointView(
            String node,
            String status,
            long elapsedMs,
            Map<String, Object> input,
            Map<String, Object> output,
            java.time.Instant startedAt,
            java.time.Instant finishedAt) {
    }
}
