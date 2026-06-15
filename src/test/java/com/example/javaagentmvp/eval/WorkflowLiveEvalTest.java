package com.example.javaagentmvp.eval;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowExecutionResult;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowRunStatus;
import com.example.javaagentmvp.admissionworkflow.service.AdmissionReportWorkflowService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles({"eval", "online"})
@Tag("eval-live")
@EnabledIfEnvironmentVariable(named = "EVAL_LIVE", matches = "1")
class WorkflowLiveEvalTest {

    @Autowired
    private AdmissionReportWorkflowService admissionReportWorkflowService;

    @Test
    void runsLiveWorkflowCasesAndWritesReport() throws Exception {
        List<EvalCaseLoader.WorkflowEvalCase> cases = EvalCaseLoader.loadWorkflowCases(
                EvalCaseLoader.projectRoot().resolve("eval/cases/workflow_live.jsonl"));
        assertThat(cases).isNotEmpty();

        List<EvalReportWriter.EvalResult> results = new ArrayList<>();
        for (EvalCaseLoader.WorkflowEvalCase evalCase : cases) {
            long startedAt = System.currentTimeMillis();
            String notes = "";
            boolean passed = false;
            try {
                WorkflowExecutionResult result =
                        admissionReportWorkflowService.runReportSync(1L, null, evalCase.input());
                long elapsedMs = System.currentTimeMillis() - startedAt;
                passed = assertLiveCase(evalCase, result, elapsedMs);
                notes = passed ? "ok" : buildFailureNotes(evalCase, result);
                results.add(new EvalReportWriter.EvalResult(evalCase.id(), passed, elapsedMs, notes));
            }
            catch (Exception ex) {
                long elapsedMs = System.currentTimeMillis() - startedAt;
                results.add(new EvalReportWriter.EvalResult(evalCase.id(), false, elapsedMs, ex.getMessage()));
            }
        }

        Path reportPath = EvalCaseLoader.projectRoot().resolve("eval/reports/latest.md");
        EvalReportWriter.writeMarkdown(reportPath, "Workflow Live Eval", results);

        long passedCount = results.stream().filter(EvalReportWriter.EvalResult::passed).count();
        assertThat(passedCount)
                .as("live eval pass count (see %s)", reportPath)
                .isEqualTo(results.size());
    }

    private static boolean assertLiveCase(
            EvalCaseLoader.WorkflowEvalCase evalCase,
            WorkflowExecutionResult result,
            long elapsedMs) {
        if (evalCase.expectStatus() != null
                && !evalCase.expectStatus().equals(result.status().name())) {
            return false;
        }
        if (elapsedMs > evalCase.maxLatencyMs()) {
            return false;
        }
        if (evalCase.expectIntent() != null
                && !evalCase.expectIntent().equals(String.valueOf(result.result().get("intent")))) {
            return false;
        }
        if (evalCase.requireScoreResult() && !result.result().containsKey("scoreResult")) {
            return false;
        }
        if (evalCase.requirePolicySources() && !result.result().containsKey("policySources")) {
            return false;
        }
        return result.status() == WorkflowRunStatus.SUCCEEDED;
    }

    private static String buildFailureNotes(
            EvalCaseLoader.WorkflowEvalCase evalCase,
            WorkflowExecutionResult result) {
        return "status=" + result.status()
                + " intent=" + result.result().get("intent")
                + " error=" + result.errorMessage();
    }
}
