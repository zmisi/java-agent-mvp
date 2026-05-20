package com.example.javaagentmvp.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ChatMemory} backed by PostgreSQL ({@code agent_ui.chat_memory_message}).
 * {@link #get(String)} returns the last {@code maxMessages} messages in chronological order.
 */
public final class PostgresChatMemory implements ChatMemory {

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final int maxMessages;

    public PostgresChatMemory(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, int maxMessages) {
        this.jdbcTemplate = jdbcTemplate;
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
            jdbcTemplate.update(
                    """
                            INSERT INTO agent_ui.chat_memory_message (conversation_id, payload)
                            VALUES (?, CAST(? AS jsonb))
                            """,
                    conversationId,
                    json);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        List<String> jsonRows = jdbcTemplate.query(
                """
                        SELECT m.payload::text
                        FROM agent_ui.chat_memory_message m
                        JOIN (
                            SELECT id
                            FROM agent_ui.chat_memory_message
                            WHERE conversation_id = ?
                            ORDER BY id DESC
                            LIMIT ?
                        ) t ON m.id = t.id
                        ORDER BY m.id ASC
                        """,
                (rs, rowNum) -> rs.getString(1),
                conversationId,
                maxMessages);
        if (jsonRows.isEmpty()) {
            return Collections.emptyList();
        }
        return MessagePayloadCodec.fromJsonRows(jsonRows, objectMapper);
    }

    @Override
    public void clear(String conversationId) {
        jdbcTemplate.update(
                "DELETE FROM agent_ui.chat_memory_message WHERE conversation_id = ?",
                conversationId);
    }

    public List<TranscriptRow> listTranscript(String conversationId) {
        return jdbcTemplate.query(
                """
                        SELECT id, created_at, payload::text
                        FROM agent_ui.chat_memory_message
                        WHERE conversation_id = ?
                        ORDER BY id ASC
                        """,
                (rs, rowNum) -> {
                    long id = rs.getLong("id");
                    String createdAt = rs.getString("created_at");
                    String payloadJson = rs.getString("payload");
                    try {
                        JsonNode node = objectMapper.readTree(payloadJson);
                        return new TranscriptRow(
                                id,
                                createdAt,
                                MessagePayloadCodec.displayRole(node),
                                MessagePayloadCodec.toDisplayText(node));
                    }
                    catch (JsonProcessingException ex) {
                        throw new IllegalStateException(ex);
                    }
                },
                conversationId);
    }

    public record TranscriptRow(long id, String createdAt, String role, String text) {
    }
}
