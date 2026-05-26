package com.example.javaagentmvp.dbagent.provisioning;

import com.example.javaagentmvp.dbagent.provisioning.persistence.mapper.ProvisioningMapper;
import com.example.javaagentmvp.dbagent.provisioning.persistence.model.ProvisioningRequestRow;
import com.example.javaagentmvp.dbagent.provisioning.persistence.model.ProvisioningStepRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class ProvisioningJobExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningJobExecutor.class);

    private final ProvisioningMapper provisioningMapper;
    private final ProvisioningStepRunner stepRunner;

    public ProvisioningJobExecutor(ProvisioningMapper provisioningMapper, ProvisioningStepRunner stepRunner) {
        this.provisioningMapper = provisioningMapper;
        this.stepRunner = stepRunner;
    }

    @Async
    public void execute(ProvisioningJobContext ctx) {
        String osFamily = null;
        String connectionHint = null;
        log.info(
                "Provisioning job {} started host={} database={} pgMajor={}",
                ctx.requestId(),
                ctx.host(),
                ctx.databaseName(),
                ctx.pgMajorVersion());
        try {
            for (String stepName : ProvisioningSteps.ORDER) {
                markStepRunning(ctx.requestId(), stepName);
                log.info("Provisioning job {} step {} started", ctx.requestId(), stepName);
                ProvisioningStepRunner.StepOutcome outcome;
                if (ProvisioningSteps.CHECK_PG_VERSION.equals(stepName)) {
                    outcome = stepRunner.runStep(stepName, ctx, osFamily);
                } else if (ProvisioningSteps.INSTALL_PG18.equals(stepName)) {
                    ProvisioningStepRow checkStep = findStep(ctx.requestId(), ProvisioningSteps.CHECK_PG_VERSION);
                    if (checkStep != null && ProvisioningStepStatus.SKIPPED.name().equals(checkStep.getStatus())) {
                        outcome = ProvisioningStepRunner.StepOutcome.skipped("PostgreSQL 18 already present");
                    } else {
                        outcome = stepRunner.runStep(stepName, ctx, osFamily);
                    }
                } else {
                    outcome = stepRunner.runStep(stepName, ctx, osFamily);
                }
                if (outcome.osFamily() != null && !outcome.osFamily().isBlank()) {
                    osFamily = outcome.osFamily();
                }
                if (outcome.connectionHint() != null) {
                    connectionHint = outcome.connectionHint();
                }
                finishStep(ctx.requestId(), stepName, outcome);
                log.info(
                        "Provisioning job {} step {} finished status={} log={}",
                        ctx.requestId(),
                        stepName,
                        outcome.status(),
                        summarize(outcome.log()));
                if (outcome.status() == ProvisioningStepStatus.FAILED) {
                    log.warn(
                            "Provisioning job {} failed at step {}: {}",
                            ctx.requestId(),
                            stepName,
                            summarize(outcome.log()));
                    failRequest(ctx.requestId(), outcome.log(), connectionHint);
                    return;
                }
            }
            succeedRequest(ctx.requestId(), connectionHint);
            log.info("Provisioning job {} succeeded", ctx.requestId());
        } catch (Exception ex) {
            log.error("Provisioning job {} failed", ctx.requestId(), ex);
            failRunningStep(ctx.requestId(), ex.getMessage());
            failRequest(ctx.requestId(), ex.getMessage(), connectionHint);
        }
    }

    private ProvisioningStepRow findStep(String requestId, String stepName) {
        return provisioningMapper.listStepsByRequestId(requestId).stream()
                .filter(s -> stepName.equals(s.getStepName()))
                .findFirst()
                .orElse(null);
    }

    private void failRunningStep(String requestId, String message) {
        provisioningMapper.listStepsByRequestId(requestId).stream()
                .filter(s -> ProvisioningStepStatus.RUNNING.name().equals(s.getStatus()))
                .findFirst()
                .ifPresent(step -> {
                    ProvisioningStepRunner.StepOutcome outcome =
                            ProvisioningStepRunner.StepOutcome.fail(ProvisioningLogRedactor.redact(message));
                    finishStep(requestId, step.getStepName(), outcome);
                });
    }

    private void markStepRunning(String requestId, String stepName) {
        ProvisioningStepRow row = new ProvisioningStepRow();
        row.setRequestId(requestId);
        row.setStepName(stepName);
        row.setStatus(ProvisioningStepStatus.RUNNING.name());
        row.setLogText("Running " + stepName + "...");
        row.setStartedAt(Instant.now().toString());
        provisioningMapper.updateStep(row);
    }

    private void finishStep(String requestId, String stepName, ProvisioningStepRunner.StepOutcome outcome) {
        ProvisioningStepRow row = new ProvisioningStepRow();
        row.setRequestId(requestId);
        row.setStepName(stepName);
        row.setStatus(outcome.status().name());
        row.setLogText(outcome.log());
        row.setFinishedAt(Instant.now().toString());
        provisioningMapper.updateStep(row);
    }

    private void succeedRequest(String requestId, String connectionHint) {
        ProvisioningRequestRow row = new ProvisioningRequestRow();
        row.setId(requestId);
        row.setStatus(ProvisioningStatus.SUCCEEDED.name());
        row.setConnectionHint(connectionHint);
        row.setUpdatedAt(Instant.now().toString());
        provisioningMapper.updateRequestStatus(row);
    }

    private void failRequest(String requestId, String error, String connectionHint) {
        ProvisioningRequestRow row = new ProvisioningRequestRow();
        row.setId(requestId);
        row.setStatus(ProvisioningStatus.FAILED.name());
        row.setErrorSummary(ProvisioningLogRedactor.redact(error));
        row.setConnectionHint(connectionHint);
        row.setUpdatedAt(Instant.now().toString());
        provisioningMapper.updateRequestStatus(row);
    }

    private static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
    }
}
