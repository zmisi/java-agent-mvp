package com.example.javaagentmvp.chat;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;

/** Loads prior user turns from chat memory for workflow multiturn compile. */
@Service
public class ConversationPriorUserMessagesResolver {

    private final PostgresChatMemory postgresChatMemory;

    public ConversationPriorUserMessagesResolver(PostgresChatMemory postgresChatMemory) {
        this.postgresChatMemory = postgresChatMemory;
    }

    public List<String> resolveNewestFirst(String conversationId, String currentUserMessage) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        List<Message> history = postgresChatMemory.get(conversationId);
        return UserTurnContextExtractor.priorUserMessagesFromHistory(history, currentUserMessage);
    }
}
