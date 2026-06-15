package com.example.javaagentmvp.admissionworkflow.service;

import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.PostgresChatMemory;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.TranscriptBuilder;
import com.example.javaagentmvp.rag.RagSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class WorkflowConversationPersistence {

    private final PostgresChatMemory postgresChatMemory;
    private final AgentConversationRepository conversationRepository;

    public WorkflowConversationPersistence(
            PostgresChatMemory postgresChatMemory,
            AgentConversationRepository conversationRepository) {
        this.postgresChatMemory = postgresChatMemory;
        this.conversationRepository = conversationRepository;
    }

    public void persistReport(
            String conversationId,
            AuthenticatedUser user,
            String userMessage,
            String assistantMessage,
            List<ChatTable> tables,
            List<RagSource> sources) {
        String persistedAssistant = appendPolicySourcesFooter(assistantMessage, sources);
        List<Message> messages = new ArrayList<>();
        if (!matchesLatestUserMessage(conversationId, userMessage)) {
            messages.add(new UserMessage(userMessage));
        }
        messages.add(new AssistantMessage(persistedAssistant));
        postgresChatMemory.add(conversationId, messages);
        if (tables != null && !tables.isEmpty()) {
            postgresChatMemory.attachUiTablesToLatestAssistant(conversationId, tables);
        }
        touchConversation(conversationId, userMessage, user);
    }

    private static String appendPolicySourcesFooter(String text, List<RagSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return text;
        }
        String titles = sources.stream()
                .map(source -> source.title() != null && !source.title().isBlank()
                        ? source.title()
                        : source.source())
                .filter(title -> title != null && !title.isBlank())
                .distinct()
                .limit(3)
                .collect(Collectors.joining("、"));
        if (titles.isBlank()) {
            return text;
        }
        return text + "\n\n参考政策：" + titles;
    }

    private boolean matchesLatestUserMessage(String conversationId, String userMessage) {
        String normalized = userMessage == null ? "" : userMessage.strip();
        if (normalized.isEmpty()) {
            return false;
        }
        List<TranscriptBuilder.TranscriptRow> transcript = postgresChatMemory.listTranscript(conversationId);
        for (int i = transcript.size() - 1; i >= 0; i--) {
            TranscriptBuilder.TranscriptRow row = transcript.get(i);
            if ("user".equals(row.role())) {
                String existing = row.text() == null ? "" : row.text().strip();
                return normalized.equals(existing);
            }
        }
        return false;
    }

    private void touchConversation(String conversationId, String message, AuthenticatedUser user) {
        Instant now = Instant.now();
        Long ownerScope = user.role() == UserRole.ADMIN ? null : user.userId();
        conversationRepository.touchUpdatedAt(conversationId, now, ownerScope);
        conversationRepository.updateTitleIfDefault(conversationId, trimTitle(message), now, ownerScope);
    }

    private static String trimTitle(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.length() <= 40) {
            return normalized.isEmpty() ? "New chat" : normalized;
        }
        return normalized.substring(0, 40) + "…";
    }
}
