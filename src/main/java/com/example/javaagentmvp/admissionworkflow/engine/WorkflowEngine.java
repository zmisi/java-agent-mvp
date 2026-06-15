package com.example.javaagentmvp.admissionworkflow.engine;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import com.example.javaagentmvp.observability.AgentMetrics;
import com.example.javaagentmvp.observability.WorkflowTraceSupport;
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
    private final AgentMetrics agentMetrics;
    private final WorkflowTraceSupport workflowTraceSupport;

    public WorkflowEngine(
            WorkflowRunRepository workflowRunRepository,
            AdmissionWorkflowProperties properties,
            AgentMetrics agentMetrics,
            WorkflowTraceSupport workflowTraceSupport) {
        this.workflowRunRepository = workflowRunRepository;
        this.properties = properties;
        this.agentMetrics = agentMetrics;
        this.workflowTraceSupport = workflowTraceSupport;
    }

    public WorkflowExecutionResult execute(
            WorkflowDefinition definition,
            Long userId,
            String conversationId,
            String inputMessage) {
        return executeSync(definition, userId, conversationId, inputMessage);
    }

    public WorkflowExecutionResult executeSync(
            WorkflowDefinition definition,
            Long userId,
            String conversationId,
            String inputMessage) {
        String runId = workflowRunRepository.createPendingRun(
                definition.workflowType(), userId, conversationId, inputMessage);
        workflowRunRepository.markRunning(runId);
        return executeExisting(definition, runId);
    }

    public String enqueue(
            WorkflowDefinition definition,
            Long userId,
            String conversationId,
            String inputMessage,
            java.util.function.Consumer<String> queuePublisher) {
        String runId = workflowRunRepository.createPendingRun(
                definition.workflowType(), userId, conversationId, inputMessage);
        queuePublisher.accept(runId);
        log.info("[WORKFLOW runId={}] enqueued type={}", runId, definition.workflowType());
        return runId;
    }

    public WorkflowExecutionResult executeExisting(WorkflowDefinition definition, String runId) {
        WorkflowRunSummaryRow run = workflowRunRepository.findSummary(runId)
                .orElseThrow(() -> new IllegalStateException("workflow run not found: " + runId));
        return workflowTraceSupport.observeWorkflowRun(
                runId,
                definition.workflowType(),
                () -> runNodes(definition, runId, run.inputMessage()));
    }

    private WorkflowExecutionResult runNodes(WorkflowDefinition definition, String runId, String inputMessage) {
        WorkflowContext context = new WorkflowContext(runId, inputMessage);
        List<WorkflowCheckpointSummary> summaries = new ArrayList<>();
        int sequence = 1;

        log.info("[WORKFLOW runId={}] start type={} message={}", runId, definition.workflowType(), inputMessage);

        for (WorkflowNode node : definition.nodes()) {
            Instant startedAt = Instant.now();
            Map<String, Object> nodeInput = context.nodeInputSnapshot();
            WorkflowNodeResult nodeResult;
            try {
                nodeResult = workflowTraceSupport.observeNode(
                        runId,
                        node.name(),
                        nodeInput,
                        () -> node.execute(context));
            }
            catch (RuntimeException ex) {
                return handleNodeFailure(runId, definition.workflowType(), node, sequence, nodeInput, startedAt, summaries, ex);
            }

            Instant finishedAt = Instant.now();
            long elapsedMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
            agentMetrics.recordWorkflowNode(node.name(), nodeResult.status().name(), elapsedMs);
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
                agentMetrics.recordWorkflowRun(definition.workflowType(), WorkflowRunStatus.FAILED.name());
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
        agentMetrics.recordWorkflowRun(definition.workflowType(), WorkflowRunStatus.SUCCEEDED.name());
        log.info("[WORKFLOW runId={}] succeeded checkpoints={}", runId, summaries.size());
        return new WorkflowExecutionResult(
                runId, WorkflowRunStatus.SUCCEEDED, finalResult, null, List.copyOf(summaries));
    }

    private WorkflowExecutionResult handleNodeFailure(
            String runId,
            String workflowType,
            WorkflowNode node,
            int sequence,
            Map<String, Object> nodeInput,
            Instant startedAt,
            List<WorkflowCheckpointSummary> summaries,
            RuntimeException ex) {
        Instant finishedAt = Instant.now();
        long elapsedMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli();
        Map<String, Object> failureOutput = Map.of("reason", ex.getMessage());
        agentMetrics.recordWorkflowNode(node.name(), CheckpointStatus.FAILED.name(), elapsedMs);
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
        agentMetrics.recordWorkflowRun(workflowType, WorkflowRunStatus.FAILED.name());
        log.error("[WORKFLOW runId={}] node={} failed elapsedMs={} error={}",
                runId, node.name(), elapsedMs, ex.getMessage(), ex);
        return new WorkflowExecutionResult(
                runId, WorkflowRunStatus.FAILED, Map.of(), ex.getMessage(), List.copyOf(summaries));
    }
}
