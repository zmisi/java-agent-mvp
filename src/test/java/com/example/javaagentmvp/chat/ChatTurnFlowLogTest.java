package com.example.javaagentmvp.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatTurnFlowLogTest {

    @AfterEach
    void tearDown() {
        ChatTurnFlowLog.clear();
    }

    @Test
    void recordsStepSequenceAndFillsSkippedStepsOnEnd() {
        ChatTurnFlowLog.begin("conv-1", "630分能上什么专业");
        ChatTurnFlowLog.step(ChatTurnFlowLog.Step.COMPILE_IR, "source=local task=search_majors");
        ChatTurnFlowLog.step(ChatTurnFlowLog.Step.ROUTE_DECISION, "continue");
        ChatTurnFlowLog.end("tables=0");

        assertThat(ChatTurnFlowLog.loggedStepsForTest()).isEmpty();
    }

    @Test
    void clarificationShortCircuitMarksLaterStepsSkipped() {
        ChatTurnFlowLog.begin("conv-2", "长三角大学");
        ChatTurnFlowLog.step(ChatTurnFlowLog.Step.COMPILE_IR, "needs=score");
        ChatTurnFlowLog.step(ChatTurnFlowLog.Step.ROUTE_DECISION, "clarification");
        ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.TASK_PROMPT, "clarification");
        ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.RAG_RETRIEVE, "clarification");
        ChatTurnFlowLog.skipped(ChatTurnFlowLog.Step.MCP_PROCESS, "clarification");
        ChatTurnFlowLog.end("clarification=true");

        assertThat(ChatTurnFlowLog.active()).isFalse();
    }
}
