package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.chat.persistence.mapper.ChatMemoryMessageMapper;
import com.example.javaagentmvp.chat.persistence.model.ChatMemoryMessageRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.List;

/**
 * {@link ChatMemory} backed by PostgreSQL ({@code agent_ui.chat_memory_message}).
 * {@link #get(String)} returns the last {@code maxMessages} messages in chronological order.
 */
public final class PostgresChatMemory implements ChatMemory {

    private final ChatMemoryMessageMapper chatMemoryMessageMapper;

    private final ObjectMapper objectMapper;

    private final int maxMessages;

    public PostgresChatMemory(
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            ObjectMapper objectMapper,
            int maxMessages) {
        this.chatMemoryMessageMapper = chatMemoryMessageMapper;
        this.objectMapper = objectMapper;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            JsonNode payload = MessagePayloadCodec.toJson(message, objectMapper);
            String json;
            try {
                json = objectMapper.writeValueAsString(payload);
            }
            catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to serialize chat message", ex);
            }
            chatMemoryMessageMapper.insertMessage(conversationId, json);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        List<String> jsonRows = chatMemoryMessageMapper.selectRecentPayloadJson(conversationId, maxMessages);
        if (jsonRows.isEmpty()) {
            return Collections.emptyList();
        }
        return MessagePayloadCodec.fromJsonRows(jsonRows, objectMapper);
    }

    @Override
    public void clear(String conversationId) {
        chatMemoryMessageMapper.deleteByConversationId(conversationId);
    }

    public List<TranscriptRow> listTranscript(String conversationId) {
        return chatMemoryMessageMapper.selectTranscriptByConversationId(conversationId).stream()
                .map(this::toTranscriptRow)
                .toList();
    }

    private TranscriptRow toTranscriptRow(ChatMemoryMessageRow row) {
        try {
            JsonNode node = objectMapper.readTree(row.getPayloadJson());
            return new TranscriptRow(
                    row.getId(),
                    row.getCreatedAt(),
                    MessagePayloadCodec.displayRole(node),
                    MessagePayloadCodec.toDisplayText(node));
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record TranscriptRow(long id, String createdAt, String role, String text) {
    }
}
