package com.example.javaagentmvp.web;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.auth.AuthInterceptor;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.ConversationAccessService;
import com.example.javaagentmvp.chat.context.ChatContextUsageRegistry;
import com.example.javaagentmvp.chat.context.ConversationCompactionService;
import com.example.javaagentmvp.chat.context.ConversationTurnSummaryBuffer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerCompactTest {

    @Test
    void compactPreviewDoesNotTouchConversationUpdatedAt() {
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ConversationAccessService conversationAccess = mock(ConversationAccessService.class);
        ChatController controller = controller(compactionService, conversationRepository, conversationAccess);

        String conversationId = "c-preview";
        when(compactionService.preview(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-preview", 10, 4, 1200, 350));

        ChatController.CompactReplyDto reply = controller.compactPreview(conversationId, "local", authedRequest(1L));

        assertThat(reply.summary()).isEqualTo("summary-preview");
        verify(conversationAccess).requireAccess(conversationId, guestUser(1L));
        verify(compactionService, times(1)).preview(conversationId);
        verify(compactionService, never()).compact(conversationId);
        verify(conversationRepository, never()).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void compactReviewDoesNotTouchConversationUpdatedAt() {
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ConversationAccessService conversationAccess = mock(ConversationAccessService.class);
        ChatController controller = controller(compactionService, conversationRepository, conversationAccess);

        String conversationId = "c-review";
        when(compactionService.preview(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-review", 14, 6, 2100, 700));

        ChatController.CompactReplyDto reply = controller.compactReview(conversationId, "local", authedRequest(1L));

        assertThat(reply.summary()).isEqualTo("summary-review");
        verify(conversationAccess).requireAccess(conversationId, guestUser(1L));
        verify(compactionService, times(1)).preview(conversationId);
        verify(conversationRepository, never()).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void compactExecutesAndTouchesConversationUpdatedAt() {
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ConversationAccessService conversationAccess = mock(ConversationAccessService.class);
        ChatController controller = controller(compactionService, conversationRepository, conversationAccess);

        String conversationId = "c-exec";
        when(compactionService.compact(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-exec", 12, 5, 1800, 500));

        ChatController.CompactReplyDto reply = controller.compact(conversationId, "local", authedRequest(2L));

        assertThat(reply.summary()).isEqualTo("summary-exec");
        verify(conversationRepository, times(1)).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(2L));
    }

    @Test
    void compactExecuteTouchesConversationUpdatedAt() {
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ConversationAccessService conversationAccess = mock(ConversationAccessService.class);
        ChatController controller = controller(compactionService, conversationRepository, conversationAccess);

        String conversationId = "c-execute";
        when(compactionService.compact(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-execute", 9, 3, 1400, 420));

        controller.compactExecute(conversationId, "local", authedRequest(3L));

        verify(conversationRepository, times(1)).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(3L));
    }

    private static ChatController controller(
            ConversationCompactionService compactionService,
            AgentConversationRepository conversationRepository,
            ConversationAccessService conversationAccess) {
        return new ChatController(
                mock(ChatClient.class),
                mock(QwenApiLoggingAdvisor.class),
                conversationRepository,
                mock(ChatContextUsageRegistry.class),
                compactionService,
                mock(ConversationTurnSummaryBuffer.class),
                conversationAccess,
                "local");
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
