package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.chat.ChatTurnFlowLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UnsupportedConstraintRecorder {

    private static final Logger log = LoggerFactory.getLogger(UnsupportedConstraintRecorder.class);

    private final JdbcTemplate jdbcTemplate;
    private final AdmissionCompilerProperties properties;

    public UnsupportedConstraintRecorder(
            JdbcTemplate jdbcTemplate,
            AdmissionCompilerProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public void record(AdmissionQueryIr query, String compilerSource) {
        record(query, compilerSource, null);
    }

    public void record(AdmissionQueryIr query, String compilerSource, String userMessage) {
        if (!properties.recordUnsupported()) {
            log.debug("UnsupportedConstraint recording disabled by app.admission-compiler.record-unsupported");
            return;
        }
        if (query == null || !query.hasUnsupportedConstraints()) {
            return;
        }
        CompileRecordContext.State context = resolveContext();
        String conversationId = normalizeConversationId(context);
        Long userId = context == null ? null : context.userId();
        String channel = context == null ? "chat" : context.channel();
        String task = query.task();
        String rawMessage = resolveUserMessage(context, query, userMessage);

        for (UnsupportedConstraintIr constraint : query.unsupportedConstraints()) {
            insertEvent(
                    conversationId,
                    userId,
                    channel,
                    task,
                    rawMessage,
                    constraint,
                    compilerSource);
        }
    }

    private static String resolveUserMessage(
            CompileRecordContext.State context,
            AdmissionQueryIr query,
            String explicitUserMessage) {
        if (explicitUserMessage != null && !explicitUserMessage.isBlank()) {
            return explicitUserMessage;
        }
        if (context != null && context.userMessage() != null && !context.userMessage().isBlank()) {
            return context.userMessage();
        }
        return query.rawMessage() == null ? "" : query.rawMessage();
    }

    private void insertEvent(
            String conversationId,
            Long userId,
            String channel,
            String task,
            String rawMessage,
            UnsupportedConstraintIr constraint,
            String compilerSource) {
        UUID eventId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO agent_ui.unsupported_constraint_event (
                                id, conversation_id, user_id, channel, task, raw_message,
                                raw_phrase, constraint_type, reason, compiler_source, created_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    ps -> {
                        ps.setObject(1, eventId, Types.OTHER);
                        ps.setString(2, conversationId);
                        if (userId == null) {
                            ps.setNull(3, Types.BIGINT);
                        }
                        else {
                            ps.setLong(3, userId);
                        }
                        ps.setString(4, channel);
                        ps.setString(5, task);
                        ps.setString(6, rawMessage);
                        ps.setString(7, constraint.rawPhrase());
                        ps.setString(8, constraint.constraintType());
                        ps.setString(9, constraint.reason());
                        ps.setString(10, compilerSource);
                        ps.setTimestamp(11, Timestamp.from(Instant.now()));
                    });
            log.info(
                    "UnsupportedConstraint recorded id={} type={} phrase={} message={} conv={} user={}",
                    eventId,
                    constraint.constraintType(),
                    constraint.rawPhrase(),
                    truncate(rawMessage),
                    conversationId,
                    userId);
        }
        catch (DataIntegrityViolationException ex) {
            if (conversationId != null || userId != null) {
                log.warn(
                        "Failed to record unsupported constraint with correlation ids; retrying without FK fields "
                                + "id={} type={} phrase={} conv={} user={}: {}",
                        eventId,
                        constraint.constraintType(),
                        constraint.rawPhrase(),
                        conversationId,
                        userId,
                        ex.getMessage());
                insertEventWithoutCorrelation(
                        eventId,
                        channel,
                        task,
                        rawMessage,
                        constraint,
                        compilerSource);
                return;
            }
            log.error(
                    "Failed to record unsupported constraint id={} type={} phrase={}",
                    eventId,
                    constraint.constraintType(),
                    constraint.rawPhrase(),
                    ex);
        }
        catch (RuntimeException ex) {
            log.error(
                    "Failed to record unsupported constraint id={} type={} phrase={}",
                    eventId,
                    constraint.constraintType(),
                    constraint.rawPhrase(),
                    ex);
        }
    }

    private void insertEventWithoutCorrelation(
            UUID eventId,
            String channel,
            String task,
            String rawMessage,
            UnsupportedConstraintIr constraint,
            String compilerSource) {
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO agent_ui.unsupported_constraint_event (
                                id, conversation_id, user_id, channel, task, raw_message,
                                raw_phrase, constraint_type, reason, compiler_source, created_at
                            ) VALUES (?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    ps -> {
                        ps.setObject(1, eventId, Types.OTHER);
                        ps.setString(2, channel);
                        ps.setString(3, task);
                        ps.setString(4, rawMessage);
                        ps.setString(5, constraint.rawPhrase());
                        ps.setString(6, constraint.constraintType());
                        ps.setString(7, constraint.reason());
                        ps.setString(8, compilerSource);
                        ps.setTimestamp(9, Timestamp.from(Instant.now()));
                    });
        }
        catch (RuntimeException retryEx) {
            log.error(
                    "Failed to record unsupported constraint id={} type={} phrase={}",
                    eventId,
                    constraint.constraintType(),
                    constraint.rawPhrase(),
                    retryEx);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 80 ? value : value.substring(0, 80) + "...";
    }

    private static CompileRecordContext.State resolveContext() {
        CompileRecordContext.State explicit = CompileRecordContext.current();
        if (explicit != null) {
            return explicit;
        }
        if (ChatTurnFlowLog.active()) {
            String conversationId = ChatTurnFlowLog.conversationId();
            if (conversationId != null && !conversationId.isBlank() && !"?".equals(conversationId)) {
                return new CompileRecordContext.State(conversationId, null, "chat", null);
            }
        }
        return null;
    }

    private static String normalizeConversationId(CompileRecordContext.State context) {
        if (context == null || context.conversationId() == null || context.conversationId().isBlank()) {
            return null;
        }
        String id = context.conversationId().strip();
        return "?".equals(id) ? null : id;
    }
}
