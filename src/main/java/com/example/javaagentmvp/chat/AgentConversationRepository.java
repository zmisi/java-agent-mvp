package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.chat.persistence.mapper.ConversationMapper;
import com.example.javaagentmvp.chat.persistence.model.ConversationRecord;
import com.example.javaagentmvp.chat.persistence.model.ConversationSummaryRow;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentConversationRepository {

    private final ConversationMapper conversationMapper;

    public AgentConversationRepository(ConversationMapper conversationMapper) {
        this.conversationMapper = conversationMapper;
    }

    public void insert(String id, String title, Instant now) {
        conversationMapper.insert(new ConversationRecord(id, title, now, now));
    }

    public void insertIfMissing(String id, String title, Instant now) {
        conversationMapper.insertIfMissing(new ConversationRecord(id, title, now, now));
    }

    public boolean exists(String id) {
        return conversationMapper.countById(id) > 0;
    }

    public void touchUpdatedAt(String id, Instant now) {
        conversationMapper.touchUpdatedAt(id, now);
    }

    public void updateTitleIfDefault(String id, String newTitle, Instant now) {
        conversationMapper.updateTitleIfDefault(id, newTitle, now);
    }

    public void updateTitle(String id, String newTitle, Instant now) {
        conversationMapper.updateTitle(id, newTitle, now);
    }

    public void archive(String id, Instant archivedAt) {
        conversationMapper.archiveById(id, archivedAt);
    }

    public void delete(String id) {
        conversationMapper.deleteById(id);
    }

    public List<ConversationSummary> listSummaries() {
        return conversationMapper.listSummaries().stream()
                .map(AgentConversationRepository::toSummary)
                .toList();
    }

    public Optional<String> findTitle(String id) {
        return Optional.ofNullable(conversationMapper.selectTitleById(id));
    }

    public static String newWebConversationId() {
        return UUID.randomUUID().toString();
    }

    private static ConversationSummary toSummary(ConversationSummaryRow row) {
        return new ConversationSummary(row.getId(), row.getTitle(), row.getCreatedAt(), row.getUpdatedAt());
    }

    public record ConversationSummary(String id, String title, String createdAt, String updatedAt) {
    }
}
