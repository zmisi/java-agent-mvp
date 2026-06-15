package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.chat.persistence.mapper.ChatMemoryMessageMapper;
import com.example.javaagentmvp.chat.persistence.model.ChatMemoryMessageRow;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.example.javaagentmvp.chat.ui.TranscriptBuilder;
import com.example.javaagentmvp.chat.ui.UiTableCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * {@link ChatMemory} backed by PostgreSQL ({@code agent_ui.chat_memory_message}).
 * {@link #get(String)} returns the last {@code maxMessages} messages in chronological order.
 */
public final class PostgresChatMemory implements ChatMemory {

    private final ChatMemoryMessageMapper chatMemoryMessageMapper;

    private final ObjectMapper objectMapper;

    private final TranscriptBuilder transcriptBuilder;

    private final int maxMessages;

    public PostgresChatMemory(
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            ObjectMapper objectMapper,
            McpTableExtractor mcpTableExtractor,
            int maxMessages) {
        this.chatMemoryMessageMapper = chatMemoryMessageMapper;
        this.objectMapper = objectMapper;
        this.transcriptBuilder = new TranscriptBuilder(mcpTableExtractor, objectMapper);
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

    public List<TranscriptBuilder.TranscriptRow> listTranscript(String conversationId) {
        List<ChatMemoryMessageRow> rows = chatMemoryMessageMapper.selectTranscriptByConversationId(conversationId);
        return transcriptBuilder.build(rows);
    }

    public void attachUiTablesToLatestAssistant(String conversationId, List<ChatTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        updateLatestAssistantPayload(conversationId, node -> {
            node.set("uiTables", objectMapper.valueToTree(tables));
            return true;
        });
    }

    public void replaceLatestAssistantText(String conversationId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        updateLatestAssistantPayload(conversationId, node -> {
            node.put("text", text);
            return true;
        }, false);
    }

    private void updateLatestAssistantPayload(String conversationId, Function<ObjectNode, Boolean> mutator) {
        updateLatestAssistantPayload(conversationId, mutator, true);
    }

    private void updateLatestAssistantPayload(
            String conversationId,
            Function<ObjectNode, Boolean> mutator,
            boolean skipToolCallMessages) {
        List<ChatMemoryMessageRow> rows = chatMemoryMessageMapper.selectTranscriptByConversationId(conversationId);
        for (int i = rows.size() - 1; i >= 0; i--) {
            ChatMemoryMessageRow row = rows.get(i);
            try {
                JsonNode node = objectMapper.readTree(row.getPayloadJson());
                if (!"assistant".equals(MessagePayloadCodec.displayRole(node))) {
                    continue;
                }
                if (skipToolCallMessages && TranscriptBuilder.hasToolCalls(node)) {
                    continue;
                }
                ObjectNode updated = ((ObjectNode) node).deepCopy();
                if (!Boolean.TRUE.equals(mutator.apply(updated))) {
                    return;
                }
                chatMemoryMessageMapper.updatePayloadById(row.getId(), objectMapper.writeValueAsString(updated));
                return;
            }
            catch (JsonProcessingException ex) {
                throw new IllegalStateException("Failed to update assistant payload for conversation " + conversationId, ex);
            }
        }
    }
}
