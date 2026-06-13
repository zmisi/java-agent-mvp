package com.example.javaagentmvp.web;

import com.example.javaagentmvp.auth.AuthInterceptor;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowCheckpointSummary;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowExecutionResult;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowRunStatus;
import com.example.javaagentmvp.admissionworkflow.engine.CheckpointStatus;
import com.example.javaagentmvp.admissionworkflow.service.AdmissionReportWorkflowService;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowAccessService;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowConversationPersistence;
import com.example.javaagentmvp.admissionworkflow.service.WorkflowReportPresenter;
import com.example.javaagentmvp.admissionworkflow.ui.WorkflowReportTableBuilder;
import com.example.javaagentmvp.chat.ConversationAccessService;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

class WorkflowControllerTest {

    @Test
    void runReportRequiresMessage() {
        WorkflowController controller = controller(mock(AdmissionReportWorkflowService.class));
        HttpServletRequest request = authedRequest(1L);

        assertThatThrownBy(() -> controller.runReport(new WorkflowController.WorkflowReportRequest("", null), request))
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(BAD_REQUEST));
    }

    @Test
    void runReportReturnsExecutionResultWithAssistantFields() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        WorkflowController controller = controller(service);
        HttpServletRequest request = authedRequest(7L);

        WorkflowExecutionResult execution = new WorkflowExecutionResult(
                "run-abc",
                WorkflowRunStatus.SUCCEEDED,
                Map.of(
                        "summary", "模板摘要",
                        "report", "LLM 报告正文",
                        "policySources", List.of(new RagSource("章程", "hfut/charter.md", "snippet", "hfut"))),
                null,
                List.of(new WorkflowCheckpointSummary("synthesize_report", CheckpointStatus.SUCCEEDED, 1200)));
        when(service.runReport(7L, null, "安徽620分")).thenReturn(execution);

        WorkflowController.WorkflowReportResponse response = controller.runReport(
                new WorkflowController.WorkflowReportRequest("安徽620分", null), request);

        assertThat(response.runId()).isEqualTo("run-abc");
        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.result()).containsEntry("report", "LLM 报告正文");
        assertThat(response.assistant()).isEqualTo("LLM 报告正文");
        assertThat(response.sources()).hasSize(1);
        verify(service).runReport(7L, null, "安徽620分");
    }

    @Test
    void runReportValidatesConversationAccessWhenProvided() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        ConversationAccessService conversationAccess = mock(ConversationAccessService.class);
        WorkflowConversationPersistence persistence = mock(WorkflowConversationPersistence.class);
        WorkflowController controller = new WorkflowController(
                service,
                mock(WorkflowAccessService.class),
                conversationAccess,
                presenter(),
                persistence);
        HttpServletRequest request = authedRequest(3L);

        when(service.runReport(3L, "conv-1", "问政策")).thenReturn(new WorkflowExecutionResult(
                "run-1",
                WorkflowRunStatus.SUCCEEDED,
                Map.of("summary", "ok", "report", "报告"),
                null,
                List.of()));

        controller.runReport(new WorkflowController.WorkflowReportRequest("问政策", "conv-1"), request);

        verify(conversationAccess).requireAccess("conv-1", guestUser(3L));
        verify(persistence).persistReport(
                "conv-1",
                guestUser(3L),
                "问政策",
                "报告",
                List.of(),
                List.of());
    }

    private static WorkflowController controller(AdmissionReportWorkflowService service) {
        return new WorkflowController(
                service,
                mock(WorkflowAccessService.class),
                mock(ConversationAccessService.class),
                presenter(),
                mock(WorkflowConversationPersistence.class));
    }

    private static WorkflowReportPresenter presenter() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new WorkflowReportPresenter(
                new WorkflowReportTableBuilder(new McpTableExtractor(objectMapper), objectMapper));
    }

    private static HttpServletRequest authedRequest(long userId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthInterceptor.AUTH_USER_ATTR)).thenReturn(guestUser(userId));
        return request;
    }

    private static AuthenticatedUser guestUser(long userId) {
        return new AuthenticatedUser(userId, "openid-" + userId, UserRole.GUEST, "session", "jti");
    }
}
