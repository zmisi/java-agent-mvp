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

    public void insert(String id, String title, Instant now, long userId) {
        conversationMapper.insert(new ConversationRecord(id, title, now, now, userId));
    }

    public void insertIfMissing(String id, String title, Instant now) {
        conversationMapper.insertIfMissing(new ConversationRecord(id, title, now, now, null));
    }

    public boolean exists(String id) {
        return conversationMapper.countById(id) > 0;
    }

    public boolean existsForUser(String id, long userId) {
        return conversationMapper.countByIdAndUserId(id, userId) > 0;
    }

    public void touchUpdatedAt(String id, Instant now, Long ownerUserId) {
        UserScope scope = UserScope.from(ownerUserId);
        conversationMapper.touchUpdatedAt(id, now, scope.scopeByUser(), scope.userId());
    }

    public void updateTitleIfDefault(String id, String newTitle, Instant now, Long ownerUserId) {
        UserScope scope = UserScope.from(ownerUserId);
        conversationMapper.updateTitleIfDefault(id, newTitle, now, scope.scopeByUser(), scope.userId());
    }

    public void updateTitle(String id, String newTitle, Instant now, Long ownerUserId) {
        UserScope scope = UserScope.from(ownerUserId);
        conversationMapper.updateTitle(id, newTitle, now, scope.scopeByUser(), scope.userId());
    }

    public void archive(String id, Instant archivedAt, Long ownerUserId) {
        UserScope scope = UserScope.from(ownerUserId);
        conversationMapper.archiveById(id, archivedAt, scope.scopeByUser(), scope.userId());
    }

    public void delete(String id, Long ownerUserId) {
        UserScope scope = UserScope.from(ownerUserId);
        conversationMapper.deleteById(id, scope.scopeByUser(), scope.userId());
    }

    public List<ConversationSummary> listSummaries(long userId) {
        return conversationMapper.listSummariesByUserId(userId).stream()
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

    /** null ownerUserId = admin / CLI: no user_id filter in SQL. */
    private record UserScope(boolean scopeByUser, long userId) {
        static UserScope from(Long ownerUserId) {
            if (ownerUserId == null) {
                return new UserScope(false, 0L);
            }
            return new UserScope(true, ownerUserId);
        }
    }

    public record ConversationSummary(String id, String title, String createdAt, String updatedAt) {
    }
}
