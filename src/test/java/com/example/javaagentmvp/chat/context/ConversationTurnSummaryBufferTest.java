package com.example.javaagentmvp.chat.context;

import com.example.javaagentmvp.chat.persistence.mapper.ConversationTurnSummaryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationTurnSummaryBufferTest {

    @Test
    void appendTurnPersistsSummaryRowToMapper() {
        ConversationTurnSummaryMapper mapper = mock(ConversationTurnSummaryMapper.class);
        ConversationTurnSummaryBuffer buffer = new ConversationTurnSummaryBuffer(mapper);

        buffer.appendTurn("c-1", "用户问：合肥大学特色专业有哪些？", "已找到：物流管理（中外合作办学）。");

        verify(mapper).insertNextTurnSummary(
                eq("c-1"),
                eq("用户问：合肥大学特色专业有哪些？"),
                eq("已找到：物流管理（中外合作办学）。"),
                eq("goal=用户问：合肥大学特色专业有哪些？ ; finding=已找到：物流管理（中外合作办学）。"));
    }

    @Test
    void recentReadsRowsFromMapperWithSafeLimit() {
        ConversationTurnSummaryMapper mapper = mock(ConversationTurnSummaryMapper.class);
        ConversationTurnSummaryBuffer buffer = new ConversationTurnSummaryBuffer(mapper);
        when(mapper.selectRecentSummaryRows("c-2", 120)).thenReturn(List.of("a", "b"));

        List<String> rows = buffer.recent("c-2", 999);

        assertThat(rows).containsExactly("a", "b");
        verify(mapper).selectRecentSummaryRows("c-2", 120);
    }

    @Test
    void appendTurnSkipsBlankConversationId() {
        ConversationTurnSummaryMapper mapper = mock(ConversationTurnSummaryMapper.class);
        ConversationTurnSummaryBuffer buffer = new ConversationTurnSummaryBuffer(mapper);

        buffer.appendTurn(" ", "hi", "there");

        verify(mapper, never()).insertNextTurnSummary(anyString(), anyString(), anyString(), anyString());
    }
}
