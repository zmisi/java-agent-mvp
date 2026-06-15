package com.example.javaagentmvp.admissionworkflow.async;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowExecutionResult;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowRunStatus;
import com.example.javaagentmvp.admissionworkflow.persistence.WorkflowRunRepository;
import com.example.javaagentmvp.admissionworkflow.persistence.model.WorkflowRunSummaryRow;
import com.example.javaagentmvp.admissionworkflow.service.AdmissionReportWorkflowService;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowConversationPersistence;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowReportPresenter;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.admission-workflow.async", name = "enabled", havingValue = "true")
public class WorkflowRunCompletionHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunCompletionHandler.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowReportPresenter workflowReportPresenter;
    private final WorkflowConversationPersistence workflowConversationPersistence;

    public WorkflowRunCompletionHandler(
            WorkflowRunRepository workflowRunRepository,
            WorkflowReportPresenter workflowReportPresenter,
            WorkflowConversationPersistence workflowConversationPersistence) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowReportPresenter = workflowReportPresenter;
        this.workflowConversationPersistence = workflowConversationPersistence;
    }

    public void onCompleted(WorkflowExecutionResult execution) {
        if (execution.status() != WorkflowRunStatus.SUCCEEDED) {
            return;
        }
        WorkflowRunSummaryRow row = workflowRunRepository.findSummary(execution.runId()).orElse(null);
        if (row == null || row.conversationId() == null || row.conversationId().isBlank()) {
            return;
        }
        WorkflowReportPresenter.PresentedWorkflowReport presented =
                workflowReportPresenter.present(row.inputMessage(), execution.result());
        if (presented.assistant() == null || presented.assistant().isBlank()) {
            return;
        }
        AuthenticatedUser user = new AuthenticatedUser(
                row.userId(),
                "workflow-" + row.userId(),
                UserRole.GUEST,
                "",
                "");
        workflowConversationPersistence.persistReport(
                row.conversationId(),
                user,
                row.inputMessage(),
                presented.assistant(),
                presented.tables(),
                presented.sources());
        log.info("[WORKFLOW runId={}] persisted conversation={}", execution.runId(), row.conversationId());
    }
}
