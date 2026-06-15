package com.example.javaagentmvp.admissionworkflow.async;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.service.AdmissionReportWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.admission-workflow.async",
        name = {"enabled", "consumer-enabled"},
        havingValue = "true")
public class WorkflowJobConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkflowJobConsumer.class);

    private final WorkflowJobQueue workflowJobQueue;
    private final AdmissionReportWorkflowService admissionReportWorkflowService;
    private final WorkflowRunCompletionHandler workflowRunCompletionHandler;
    private final AdmissionWorkflowProperties properties;

    private volatile boolean running;
    private Thread workerThread;

    public WorkflowJobConsumer(
            WorkflowJobQueue workflowJobQueue,
            AdmissionReportWorkflowService admissionReportWorkflowService,
            WorkflowRunCompletionHandler workflowRunCompletionHandler,
            AdmissionWorkflowProperties properties) {
        this.workflowJobQueue = workflowJobQueue;
        this.admissionReportWorkflowService = admissionReportWorkflowService;
        this.workflowRunCompletionHandler = workflowRunCompletionHandler;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        workerThread = new Thread(this::pollLoop, "workflow-job-consumer");
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("[WORKFLOW consumer] started queue={}", properties.async().queueKey());
    }

    @Override
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("[WORKFLOW consumer] stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void pollLoop() {
        long redisBackoffMs = 1_000;
        while (running) {
            try {
                workflowJobQueue.dequeue().ifPresent(this::processRun);
                redisBackoffMs = 1_000;
            }
            catch (RedisConnectionFailureException ex) {
                if (!running) {
                    break;
                }
                log.warn(
                        "[WORKFLOW consumer] Redis unavailable ({}); retry in {}ms — start Redis or disable async",
                        ex.getMostSpecificCause().getMessage(),
                        redisBackoffMs);
                sleepQuietly(redisBackoffMs);
                redisBackoffMs = Math.min(redisBackoffMs * 2, 30_000);
            }
            catch (Exception ex) {
                if (running) {
                    log.error("[WORKFLOW consumer] poll error", ex);
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void processRun(String runId) {
        if (!admissionReportWorkflowService.tryMarkRunning(runId)) {
            log.warn("[WORKFLOW consumer] skip runId={} not pending", runId);
            return;
        }
        try {
            var execution = admissionReportWorkflowService.runExisting(runId);
            workflowRunCompletionHandler.onCompleted(execution);
        }
        catch (RuntimeException ex) {
            log.error("[WORKFLOW consumer] runId={} failed", runId, ex);
        }
    }
}
