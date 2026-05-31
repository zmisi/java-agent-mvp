package com.example.javaagentmvp.chat.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationTurnSummaryMapper {

    int insertNextTurnSummary(
            @Param("conversationId") String conversationId,
            @Param("goal") String goal,
            @Param("finding") String finding,
            @Param("summaryRow") String summaryRow);

    List<String> selectRecentSummaryRows(
            @Param("conversationId") String conversationId,
            @Param("limit") int limit);
}
