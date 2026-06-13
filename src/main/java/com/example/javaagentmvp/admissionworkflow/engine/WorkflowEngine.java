package com.example.javaagentmvp.admissionworkflow.engine;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final AdmissionWorkflowProperties properties;

    public WorkflowEngine(WorkflowRunRepository workflowRunRepository, AdmissionWorkflowProperties properties) {
        this.workflowRunRepository = workflowRunRepository;
        this.properties = properties;
    }

    public WorkflowExecutionResult execute(
            WorkflowDefinition definition,
            Long userId,
            String conversationId,
            String inputMessage) {
        String runId = workflowRunRepository.createRun(
                definition.workflowType(), userId, conversationId, inputMessage);
        WorkflowContext context = new WorkflowContext(runId, inputMessage);
        List<WorkflowCheckpointSummary> summaries = new ArrayList<>();
        int sequence = 1;

        log.info("[WORKFLOW runId={}] start type={} message={}", runId, definition.workflowType(), inputMessage);

        for (WorkflowNode node : definition.nodes()) {
            Instant startedAt = Instant.now();
            Map<String, Object> nodeInput = context.nodeInputSnapshot();
            WorkflowNodeResult nodeResult;
            try {
                nodeResult = node.execute(context);
            }
            catch (RuntimeException ex) {
                Instant finishedAt = Instant.now();
                long elapsedMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
                Map<String, Object> failureOutput = Map.of("reason", ex.getMessage());
                workflowRunRepository.insertCheckpoint(
                        runId,
                        node.name(),
                        sequence,
                        CheckpointStatus.FAILED.name(),
                        nodeInput,
                        failureOutput,
                        startedAt,
                        finishedAt);
                summaries.add(new WorkflowCheckpointSummary(node.name(), CheckpointStatus.FAILED, elapsedMs));
                workflowRunRepository.markFailed(runId, node.name() + ": " + ex.getMessage());
                log.error("[WORKFLOW runId={}] node={} failed elapsedMs={} error={}",
                        runId, node.name(), elapsedMs, ex.getMessage(), ex);
                return new WorkflowExecutionResult(
                        runId, WorkflowRunStatus.FAILED, Map.of(), ex.getMessage(), List.copyOf(summaries));
            }

            Instant finishedAt = Instant.now();
            long elapsedMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
            workflowRunRepository.insertCheckpoint(
                    runId,
                    node.name(),
                    sequence,
                    nodeResult.status().name(),
                    nodeInput,
                    nodeResult.output(),
                    startedAt,
                    finishedAt);
            summaries.add(new WorkflowCheckpointSummary(node.name(), nodeResult.status(), elapsedMs));

            if (properties.logCheckpoints()) {
                log.info("[WORKFLOW runId={}] node={} status={} elapsedMs={}",
                        runId, node.name(), nodeResult.status(), elapsedMs);
            }

            if (nodeResult.isFailed()) {
                String reason = String.valueOf(nodeResult.output().getOrDefault("reason", "node failed"));
                workflowRunRepository.markFailed(runId, node.name() + ": " + reason);
                return new WorkflowExecutionResult(
                        runId, WorkflowRunStatus.FAILED, Map.of(), reason, List.copyOf(summaries));
            }

            sequence++;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> finalResult = (Map<String, Object>) context.get("finalResult");
        if (finalResult == null) {
            finalResult = Map.of("summary", "Workflow completed without formatted result.");
        }
        workflowRunRepository.markSucceeded(runId, finalResult);
        log.info("[WORKFLOW runId={}] succeeded checkpoints={}", runId, summaries.size());
        return new WorkflowExecutionResult(
                runId, WorkflowRunStatus.SUCCEEDED, finalResult, null, List.copyOf(summaries));
    }
}
