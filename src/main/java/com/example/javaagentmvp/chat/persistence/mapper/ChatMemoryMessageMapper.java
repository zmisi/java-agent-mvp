package com.example.javaagentmvp.chat.persistence.mapper;

import com.example.javaagentmvp.chat.persistence.model.ChatMemoryMessageRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatMemoryMessageMapper {

    int insertMessage(@Param("conversationId") String conversationId, @Param("payloadJson") String payloadJson);

    List<String> selectRecentPayloadJson(
            @Param("conversationId") String conversationId,
            @Param("limit") int limit);

    int deleteByConversationId(@Param("conversationId") String conversationId);

    int updatePayloadById(@Param("id") long id, @Param("payloadJson") String payloadJson);

    List<ChatMemoryMessageRow> selectTranscriptByConversationId(@Param("conversationId") String conversationId);
}
