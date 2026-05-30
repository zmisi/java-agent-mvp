package com.example.javaagentmvp.web;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.context.ChatContextUsageRegistry;
import com.example.javaagentmvp.chat.context.ConversationCompactionService;
import com.example.javaagentmvp.chat.context.ConversationTurnSummaryBuffer;
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
        ChatClient chatClient = mock(ChatClient.class);
        QwenApiLoggingAdvisor qwenApiLoggingAdvisor = mock(QwenApiLoggingAdvisor.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ChatContextUsageRegistry chatContextUsageRegistry = mock(ChatContextUsageRegistry.class);
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        ConversationTurnSummaryBuffer turnSummaryBuffer = mock(ConversationTurnSummaryBuffer.class);
        ChatController controller = new ChatController(
                chatClient,
                qwenApiLoggingAdvisor,
                conversationRepository,
                chatContextUsageRegistry,
                compactionService,
                turnSummaryBuffer,
                "local");

        String conversationId = "c-preview";
        when(conversationRepository.exists(conversationId)).thenReturn(true);
        when(compactionService.preview(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-preview", 10, 4, 1200, 350));

        ChatController.CompactReplyDto reply = controller.compactPreview(conversationId, "local");

        assertThat(reply.summary()).isEqualTo("summary-preview");
        assertThat(reply.beforeMessageCount()).isEqualTo(10);
        assertThat(reply.afterMessageCount()).isEqualTo(4);
        assertThat(reply.beforeEstimatedTokens()).isEqualTo(1200);
        assertThat(reply.afterEstimatedTokens()).isEqualTo(350);
        verify(compactionService, times(1)).preview(conversationId);
        verify(compactionService, never()).compact(conversationId);
        verify(conversationRepository, never()).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void compactReviewDoesNotTouchConversationUpdatedAt() {
        ChatClient chatClient = mock(ChatClient.class);
        QwenApiLoggingAdvisor qwenApiLoggingAdvisor = mock(QwenApiLoggingAdvisor.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ChatContextUsageRegistry chatContextUsageRegistry = mock(ChatContextUsageRegistry.class);
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        ConversationTurnSummaryBuffer turnSummaryBuffer = mock(ConversationTurnSummaryBuffer.class);
        ChatController controller = new ChatController(
                chatClient,
                qwenApiLoggingAdvisor,
                conversationRepository,
                chatContextUsageRegistry,
                compactionService,
                turnSummaryBuffer,
                "local");

        String conversationId = "c-review";
        when(conversationRepository.exists(conversationId)).thenReturn(true);
        when(compactionService.preview(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-review", 14, 6, 2100, 700));

        ChatController.CompactReplyDto reply = controller.compactReview(conversationId, "local");

        assertThat(reply.summary()).isEqualTo("summary-review");
        assertThat(reply.beforeMessageCount()).isEqualTo(14);
        assertThat(reply.afterMessageCount()).isEqualTo(6);
        assertThat(reply.beforeEstimatedTokens()).isEqualTo(2100);
        assertThat(reply.afterEstimatedTokens()).isEqualTo(700);
        verify(compactionService, times(1)).preview(conversationId);
        verify(compactionService, never()).compact(conversationId);
        verify(conversationRepository, never()).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void compactExecutesAndTouchesConversationUpdatedAt() {
        ChatClient chatClient = mock(ChatClient.class);
        QwenApiLoggingAdvisor qwenApiLoggingAdvisor = mock(QwenApiLoggingAdvisor.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ChatContextUsageRegistry chatContextUsageRegistry = mock(ChatContextUsageRegistry.class);
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        ConversationTurnSummaryBuffer turnSummaryBuffer = mock(ConversationTurnSummaryBuffer.class);
        ChatController controller = new ChatController(
                chatClient,
                qwenApiLoggingAdvisor,
                conversationRepository,
                chatContextUsageRegistry,
                compactionService,
                turnSummaryBuffer,
                "local");

        String conversationId = "c-exec";
        when(conversationRepository.exists(conversationId)).thenReturn(true);
        when(compactionService.compact(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-exec", 12, 5, 1800, 500));

        ChatController.CompactReplyDto reply = controller.compact(conversationId, "local");

        assertThat(reply.summary()).isEqualTo("summary-exec");
        assertThat(reply.beforeMessageCount()).isEqualTo(12);
        assertThat(reply.afterMessageCount()).isEqualTo(5);
        assertThat(reply.beforeEstimatedTokens()).isEqualTo(1800);
        assertThat(reply.afterEstimatedTokens()).isEqualTo(500);
        verify(compactionService, times(1)).compact(conversationId);
        verify(compactionService, never()).preview(conversationId);
        verify(conversationRepository, times(1)).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void compactExecuteTouchesConversationUpdatedAt() {
        ChatClient chatClient = mock(ChatClient.class);
        QwenApiLoggingAdvisor qwenApiLoggingAdvisor = mock(QwenApiLoggingAdvisor.class);
        AgentConversationRepository conversationRepository = mock(AgentConversationRepository.class);
        ChatContextUsageRegistry chatContextUsageRegistry = mock(ChatContextUsageRegistry.class);
        ConversationCompactionService compactionService = mock(ConversationCompactionService.class);
        ConversationTurnSummaryBuffer turnSummaryBuffer = mock(ConversationTurnSummaryBuffer.class);
        ChatController controller = new ChatController(
                chatClient,
                qwenApiLoggingAdvisor,
                conversationRepository,
                chatContextUsageRegistry,
                compactionService,
                turnSummaryBuffer,
                "local");

        String conversationId = "c-execute";
        when(conversationRepository.exists(conversationId)).thenReturn(true);
        when(compactionService.compact(conversationId)).thenReturn(new ConversationCompactionService.CompactionResult(
                "summary-execute", 9, 3, 1400, 420));

        ChatController.CompactReplyDto reply = controller.compactExecute(conversationId, "local");

        assertThat(reply.summary()).isEqualTo("summary-execute");
        assertThat(reply.beforeMessageCount()).isEqualTo(9);
        assertThat(reply.afterMessageCount()).isEqualTo(3);
        assertThat(reply.beforeEstimatedTokens()).isEqualTo(1400);
        assertThat(reply.afterEstimatedTokens()).isEqualTo(420);
        verify(compactionService, times(1)).compact(conversationId);
        verify(compactionService, never()).preview(conversationId);
        verify(conversationRepository, times(1)).touchUpdatedAt(
                org.mockito.ArgumentMatchers.eq(conversationId),
                org.mockito.ArgumentMatchers.any());
    }
}
