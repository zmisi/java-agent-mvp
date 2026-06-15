package com.example.javaagentmvp.admissionworkflow.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResolvedTurnPromptFormatterTest {

    @Test
    void formatsRankTaskBlock() {
        ResolvedTurn turn = new ResolvedTurn(
                AdmissionIntent.RANK,
                new AdmissionInputParser.ParsedAdmissionInput(600, "浙江", null, null, null),
                SlotDelta.PROVINCE,
                true);

        String block = ResolvedTurnPromptFormatter.format(turn);

        assertThat(block).contains("查位次");
        assertThat(block).contains("600分");
        assertThat(block).contains("浙江");
        assertThat(block).contains("本轮变更");
        assertThat(block).contains("getRankByScore");
    }

    @Test
    void formatsPolicyTaskBlock() {
        ResolvedTurn turn = new ResolvedTurn(
                AdmissionIntent.POLICY,
                AdmissionInputParser.parse("合工大转专业政策"),
                SlotDelta.NONE,
                false);

        String block = ResolvedTurnPromptFormatter.format(turn);

        assertThat(block).contains("查政策");
        assertThat(block).contains("禁止**调用 getMajorByScore");
        assertThat(block).contains("禁止**输出冲/稳/保");
    }

    @Test
    void returnsEmptyForUnknownIntent() {
        assertThat(ResolvedTurnPromptFormatter.format(ResolvedTurn.unknown("hello"))).isEmpty();
    }
}
