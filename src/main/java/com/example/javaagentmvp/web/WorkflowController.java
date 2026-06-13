package com.example.javaagentmvp.web;

import com.example.javaagentmvp.auth.AuthRequestSupport;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowCheckpointSummary;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowExecutionResult;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowRunStatus;
import com.example.javaagentmvp.admissionworkflow.service.AdmissionReportWorkflowService;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowAccessService;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowConversationPersistence;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowReportPresenter;
import com.example.javaagentmvp.chat.ConversationAccessService;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@ConditionalOnBean(AdmissionReportWorkflowService.class)
public class WorkflowController {

    private final AdmissionReportWorkflowService admissionReportWorkflowService;
    private final WorkflowAccessService workflowAccessService;
    private final ConversationAccessService conversationAccessService;
    private final WorkflowReportPresenter workflowReportPresenter;
    private final WorkflowConversationPersistence workflowConversationPersistence;

    public WorkflowController(
            AdmissionReportWorkflowService admissionReportWorkflowService,
            WorkflowAccessService workflowAccessService,
            ConversationAccessService conversationAccessService,
            WorkflowReportPresenter workflowReportPresenter,
            WorkflowConversationPersistence workflowConversationPersistence) {
        this.admissionReportWorkflowService = admissionReportWorkflowService;
        this.workflowAccessService = workflowAccessService;
        this.conversationAccessService = conversationAccessService;
        this.workflowReportPresenter = workflowReportPresenter;
        this.workflowConversationPersistence = workflowConversationPersistence;
    }

    @PostMapping("/report")
    public WorkflowReportResponse runReport(@RequestBody WorkflowReportRequest body, HttpServletRequest request) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        String conversationId = normalizeOptional(body.conversationId());
        if (conversationId != null) {
            conversationAccessService.requireAccess(conversationId, user);
        }

        String message = body.message().strip();
        WorkflowExecutionResult execution = admissionReportWorkflowService.runReport(
                user.userId(), conversationId, message);

        WorkflowReportPresenter.PresentedWorkflowReport presented =
                workflowReportPresenter.present(message, execution.result());

        if (conversationId != null
                && execution.status() == WorkflowRunStatus.SUCCEEDED
                && presented.assistant() != null
                && !presented.assistant().isBlank()) {
            workflowConversationPersistence.persistReport(
                    conversationId,
                    user,
                    message,
                    presented.assistant(),
                    presented.tables(),
                    presented.sources());
        }

        return toReportResponse(execution, presented);
    }

    @GetMapping("/{runId}")
    public WorkflowRunResponse getRun(@PathVariable String runId, HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        workflowAccessService.requireAccess(runId, user);
        AdmissionReportWorkflowService.WorkflowRunView run = admissionReportWorkflowService.findRun(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow run not found"));
        List<WorkflowCheckpointSummaryDto> checkpoints = admissionReportWorkflowService.listCheckpoints(runId).stream()
                .map(cp -> new WorkflowCheckpointSummaryDto(cp.node(), cp.status(), cp.elapsedMs()))
                .toList();
        return new WorkflowRunResponse(
                run.runId(),
                run.status(),
                run.workflowType(),
                run.conversationId(),
                run.inputMessage(),
                run.result(),
                run.errorMessage(),
                checkpoints);
    }

    @GetMapping("/{runId}/checkpoints")
    public WorkflowCheckpointListResponse listCheckpoints(@PathVariable String runId, HttpServletRequest request) {
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        workflowAccessService.requireAccess(runId, user);
        List<AdmissionReportWorkflowService.WorkflowCheckpointView> checkpoints =
                admissionReportWorkflowService.listCheckpoints(runId);
        if (checkpoints.isEmpty()
                && admissionReportWorkflowService.findRun(runId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workflow run not found");
        }
        return new WorkflowCheckpointListResponse(
                runId,
                checkpoints.stream()
                        .map(cp -> new WorkflowCheckpointDetailDto(
                                cp.node(),
                                cp.status(),
                                cp.elapsedMs(),
                                cp.input(),
                                cp.output(),
                                cp.startedAt(),
                                cp.finishedAt()))
                        .toList());
    }

    private WorkflowReportResponse toReportResponse(
            WorkflowExecutionResult execution,
            WorkflowReportPresenter.PresentedWorkflowReport presented) {
        List<WorkflowCheckpointSummaryDto> checkpoints = execution.checkpoints().stream()
                .map(cp -> new WorkflowCheckpointSummaryDto(
                        cp.node(), cp.status().name(), cp.elapsedMs()))
                .toList();
        return new WorkflowReportResponse(
                execution.runId(),
                execution.status().name(),
                execution.result(),
                execution.errorMessage(),
                checkpoints,
                presented.assistant(),
                presented.tables(),
                presented.sources());
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    public record WorkflowReportRequest(String message, String conversationId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowReportResponse(
            String runId,
            String status,
            Map<String, Object> result,
            String errorMessage,
            List<WorkflowCheckpointSummaryDto> checkpoints,
            String assistant,
            List<ChatTable> tables,
            List<RagSource> sources) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowRunResponse(
            String runId,
            String status,
            String workflowType,
            String conversationId,
            String inputMessage,
            Map<String, Object> result,
            String errorMessage,
            List<WorkflowCheckpointSummaryDto> checkpoints) {
    }

    public record WorkflowCheckpointSummaryDto(String node, String status, long elapsedMs) {
    }

    public record WorkflowCheckpointListResponse(
            String runId, List<WorkflowCheckpointDetailDto> checkpoints) {
    }

    public record WorkflowCheckpointDetailDto(
            String node,
            String status,
            long elapsedMs,
            Map<String, Object> input,
            Map<String, Object> output,
            java.time.Instant startedAt,
            java.time.Instant finishedAt) {
    }
}
