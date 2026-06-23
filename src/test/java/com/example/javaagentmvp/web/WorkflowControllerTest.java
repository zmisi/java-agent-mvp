package com.example.javaagentmvp.web;

import com.example.javaagentmvp.auth.AuthInterceptor;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.AsyncProperties;
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
import com.example.javaagentmvp.chat.ui.ChatTableEnrichmentService;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

class WorkflowControllerTest {

    @Test
    void runReportRequiresMessage() {
        WorkflowController controller = controller(mock(AdmissionReportWorkflowService.class), asyncDisabled());
        HttpServletRequest request = authedRequest(1L);

        assertThatThrownBy(() -> controller.runReport(
                new WorkflowController.WorkflowReportRequest("", null), null, request))
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(BAD_REQUEST));
    }

    @Test
    void runReportReturnsExecutionResultWithAssistantFieldsWhenSync() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        WorkflowController controller = controller(service, asyncDisabled());
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
        when(service.runReportSync(7L, null, "安徽620分")).thenReturn(execution);

        ResponseEntity<?> response = controller.runReport(
                new WorkflowController.WorkflowReportRequest("安徽620分", null),
                null,
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        WorkflowController.WorkflowReportResponse body = (WorkflowController.WorkflowReportResponse) response.getBody();
        assertThat(body.runId()).isEqualTo("run-abc");
        assertThat(body.status()).isEqualTo("SUCCEEDED");
        assertThat(body.result()).containsEntry("report", "LLM 报告正文");
        assertThat(body.assistant()).isEqualTo("LLM 报告正文");
        assertThat(body.sources()).hasSize(1);
        verify(service).runReportSync(7L, null, "安徽620分");
    }

    @Test
    void runReportEnqueuesWhenAsyncEnabled() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        WorkflowController controller = controller(service, asyncEnabled());
        HttpServletRequest request = authedRequest(2L);

        when(service.enqueueReport(2L, null, "安徽620分")).thenReturn("run-async");

        ResponseEntity<?> response = controller.runReport(
                new WorkflowController.WorkflowReportRequest("安徽620分", null),
                null,
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        WorkflowController.WorkflowEnqueueResponse body =
                (WorkflowController.WorkflowEnqueueResponse) response.getBody();
        assertThat(body.runId()).isEqualTo("run-async");
        assertThat(body.status()).isEqualTo("PENDING");
        verify(service).enqueueReport(2L, null, "安徽620分");
    }

    @Test
    void runReportSyncParamBypassesAsync() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        WorkflowController controller = controller(service, asyncEnabled());
        HttpServletRequest request = authedRequest(4L);

        when(service.runReportSync(4L, null, "sync please")).thenReturn(new WorkflowExecutionResult(
                "run-sync",
                WorkflowRunStatus.SUCCEEDED,
                Map.of("summary", "ok", "report", "报告"),
                null,
                List.of()));

        ResponseEntity<?> response = controller.runReport(
                new WorkflowController.WorkflowReportRequest("sync please", null),
                true,
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).runReportSync(4L, null, "sync please");
    }

    @Test
    void getReportReturns409WhileRunning() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        WorkflowController controller = controller(service, asyncEnabled());
        HttpServletRequest request = authedRequest(5L);

        when(service.findRun("run-running")).thenReturn(Optional.of(new AdmissionReportWorkflowService.WorkflowRunView(
                "run-running",
                "admission_report",
                WorkflowRunStatus.RUNNING.name(),
                "conv",
                "msg",
                Map.of(),
                null,
                Instant.now(),
                Instant.now(),
                null,
                2)));

        assertThatThrownBy(() -> controller.getReport("run-running", request))
                .satisfies(ex -> assertThat(((org.springframework.web.server.ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(CONFLICT));
    }

    @Test
    void getReportReturnsFullReportWhenSucceeded() {
        AdmissionReportWorkflowService service = mock(AdmissionReportWorkflowService.class);
        WorkflowController controller = controller(service, asyncEnabled());
        HttpServletRequest request = authedRequest(6L);

        when(service.findRun("run-done")).thenReturn(Optional.of(new AdmissionReportWorkflowService.WorkflowRunView(
                "run-done",
                "admission_report",
                WorkflowRunStatus.SUCCEEDED.name(),
                "conv",
                "安徽620",
                Map.of("summary", "ok", "report", "完整报告"),
                null,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                7)));
        when(service.listCheckpoints("run-done")).thenReturn(List.of());

        WorkflowController.WorkflowReportResponse response = controller.getReport("run-done", request);

        assertThat(response.status()).isEqualTo("SUCCEEDED");
        assertThat(response.assistant()).isEqualTo("完整报告");
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
                persistence,
                asyncDisabled());
        HttpServletRequest request = authedRequest(3L);

        when(service.runReportSync(3L, "conv-1", "问政策")).thenReturn(new WorkflowExecutionResult(
                "run-1",
                WorkflowRunStatus.SUCCEEDED,
                Map.of("summary", "ok", "report", "报告"),
                null,
                List.of()));

        controller.runReport(
                new WorkflowController.WorkflowReportRequest("问政策", "conv-1"),
                null,
                request);

        verify(conversationAccess).requireAccess("conv-1", guestUser(3L));
        verify(persistence).persistReport(
                "conv-1",
                guestUser(3L),
                "问政策",
                "报告",
                List.of(),
                List.of());
    }

    private static WorkflowController controller(
            AdmissionReportWorkflowService service,
            AdmissionWorkflowProperties properties) {
        return new WorkflowController(
                service,
                mock(WorkflowAccessService.class),
                mock(ConversationAccessService.class),
                presenter(),
                mock(WorkflowConversationPersistence.class),
                properties);
    }

    private static AdmissionWorkflowProperties asyncDisabled() {
        return new AdmissionWorkflowProperties(
                true, "admission_report", false, null, AsyncProperties.defaults());
    }

    private static AdmissionWorkflowProperties asyncEnabled() {
        return new AdmissionWorkflowProperties(
                true,
                "admission_report",
                false,
                null,
                new AsyncProperties(true, "admission-workflow:jobs", true, 5));
    }

    private static WorkflowReportPresenter presenter() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new WorkflowReportPresenter(
                new WorkflowReportTableBuilder(new McpTableExtractor(objectMapper), objectMapper),
                ChatTableEnrichmentService.noop());
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
