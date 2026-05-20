package com.example.javaagentmvp.chat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String id, String title, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                """
                        INSERT INTO agent_ui.conversation (id, title, created_at, updated_at)
                        VALUES (?, ?, ?, ?)
                        """,
                id,
                title,
                ts,
                ts);
    }

    public void insertIfMissing(String id, String title, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbcTemplate.update(
                """
                        INSERT INTO agent_ui.conversation (id, title, created_at, updated_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (id) DO NOTHING
                        """,
                id,
                title,
                ts,
                ts);
    }

    public boolean exists(String id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::int FROM agent_ui.conversation WHERE id = ?",
                Integer.class,
                id);
        return count != null && count > 0;
    }

    public void touchUpdatedAt(String id, Instant now) {
        jdbcTemplate.update(
                "UPDATE agent_ui.conversation SET updated_at = ? WHERE id = ?",
                Timestamp.from(now),
                id);
    }

    public void updateTitleIfDefault(String id, String newTitle, Instant now) {
        jdbcTemplate.update(
                """
                        UPDATE agent_ui.conversation
                        SET title = ?, updated_at = ?
                        WHERE id = ?
                          AND title IN ('新对话', 'CLI 会话')
                        """,
                newTitle,
                Timestamp.from(now),
                id);
    }

    public void updateTitle(String id, String newTitle, Instant now) {
        jdbcTemplate.update(
                """
                        UPDATE agent_ui.conversation
                        SET title = ?, updated_at = ?
                        WHERE id = ?
                        """,
                newTitle,
                Timestamp.from(now),
                id);
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM agent_ui.conversation WHERE id = ?", id);
    }

    public List<ConversationSummary> listSummaries() {
        return jdbcTemplate.query(
                """
                        SELECT id, title, created_at, updated_at
                        FROM agent_ui.conversation
                        ORDER BY updated_at DESC
                        LIMIT 200
                        """,
                (rs, rowNum) -> new ConversationSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getString("created_at"),
                        rs.getString("updated_at")));
    }

    public Optional<String> findTitle(String id) {
        List<String> titles = jdbcTemplate.query(
                "SELECT title FROM agent_ui.conversation WHERE id = ?",
                (rs, rowNum) -> rs.getString(1),
                id);
        if (titles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(titles.get(0));
    }

    public static String newWebConversationId() {
        return UUID.randomUUID().toString();
    }

    public record ConversationSummary(String id, String title, String createdAt, String updatedAt) {
    }
}
