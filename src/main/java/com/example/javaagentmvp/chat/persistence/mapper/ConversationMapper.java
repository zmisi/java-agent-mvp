package com.example.javaagentmvp.chat.persistence.mapper;

import com.example.javaagentmvp.chat.persistence.model.ConversationRecord;
import com.example.javaagentmvp.chat.persistence.model.ConversationSummaryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface ConversationMapper {

    int insert(ConversationRecord conversation);

    int insertIfMissing(ConversationRecord conversation);

    int countById(@Param("id") String id);

    int touchUpdatedAt(@Param("id") String id, @Param("updatedAt") Instant updatedAt);

    int updateTitleIfDefault(
            @Param("id") String id,
            @Param("title") String title,
            @Param("updatedAt") Instant updatedAt);

    int updateTitle(
            @Param("id") String id,
            @Param("title") String title,
            @Param("updatedAt") Instant updatedAt);

    int archiveById(@Param("id") String id, @Param("archivedAt") Instant archivedAt);

    int deleteById(@Param("id") String id);

    List<ConversationSummaryRow> listSummaries();

    String selectTitleById(@Param("id") String id);
}
