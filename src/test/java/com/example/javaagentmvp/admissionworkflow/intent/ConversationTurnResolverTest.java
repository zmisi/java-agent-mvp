package com.example.javaagentmvp.admissionworkflow.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationTurnResolverTest {

    private final ConversationTurnResolver resolver = new ConversationTurnResolver();

    @Test
    void resolvesRankQueryFromSingleTurn() {
        ResolvedTurn turn = resolver.resolve("600分在安徽省的排名", List.of(), List.of());

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.RANK);
        assertThat(turn.slots().score()).isEqualTo(600);
        assertThat(turn.slots().province()).isEqualTo("安徽");
        assertThat(turn.inheritedIntent()).isFalse();
    }

    @Test
    void inheritsRankIntentForProvinceFollowUp() {
        ResolvedTurn turn = resolver.resolve(
                "浙江呢？",
                List.of("600分在安徽省的排名"),
                List.of("rank-result-table 位次 一分一段"));

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.RANK);
        assertThat(turn.slots().score()).isEqualTo(600);
        assertThat(turn.slots().province()).isEqualTo("浙江");
        assertThat(turn.delta()).isEqualTo(SlotDelta.PROVINCE);
        assertThat(turn.inheritedIntent()).isTrue();
    }

    @Test
    void inheritsRankIntentForScoreFollowUp() {
        ResolvedTurn turn = resolver.resolve(
                "620分呢？",
                List.of("600分在安徽省的排名"),
                List.of("rank-result-table"));

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.RANK);
        assertThat(turn.slots().score()).isEqualTo(620);
        assertThat(turn.slots().province()).isEqualTo("安徽");
        assertThat(turn.delta()).isEqualTo(SlotDelta.SCORE);
        assertThat(turn.inheritedIntent()).isTrue();
    }

    @Test
    void doesNotInheritRankWhenFollowUpIsPolicy() {
        ResolvedTurn turn = resolver.resolve(
                "合工大2025年招生章程有哪些录取规则",
                List.of("600分在安徽省的排名"),
                List.of("rank-result-table"));

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.POLICY);
        assertThat(turn.inheritedIntent()).isFalse();
    }

    @Test
    void inheritsScoreIntentForParameterFollowUp() {
        ResolvedTurn turn = resolver.resolve(
                "安徽，物理类， 2025， 普通批",
                List.of("630分可以报考什么专业"),
                List.of("请提供您的所在省份和科类（物理类/历史类）"));

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(turn.slots().score()).isEqualTo(630);
        assertThat(turn.slots().province()).isEqualTo("安徽");
        assertThat(turn.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(turn.inheritedIntent()).isTrue();
    }

    @Test
    void classifiesPolicyIntentForMajorTransferQuestion() {
        ResolvedTurn turn = resolver.resolve("合工大转专业政策", List.of("630分物理类安徽可以报什么"), List.of());

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.POLICY);
        assertThat(turn.needsMcpTool()).isFalse();
        assertThat(turn.needsTaskPrompt()).isTrue();
    }

    @Test
    void switchesToScoreWhenUserAsksMajorQuery() {
        ResolvedTurn turn = resolver.resolve(
                "630分可以报什么专业",
                List.of("600分在安徽省的排名"),
                List.of("rank-result-table"));

        assertThat(turn.intent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(turn.slots().score()).isEqualTo(630);
        assertThat(turn.inheritedIntent()).isFalse();
    }

    @Test
    void mergeSlotsPrefersNewValues() {
        AdmissionInputParser.ParsedAdmissionInput base =
                new AdmissionInputParser.ParsedAdmissionInput(600, "安徽", "物理类", null, null);
        AdmissionInputParser.ParsedAdmissionInput update =
                new AdmissionInputParser.ParsedAdmissionInput(620, "浙江", null, null, null);

        AdmissionInputParser.ParsedAdmissionInput merged =
                ConversationTurnResolver.mergeSlots(base, update);

        assertThat(merged.score()).isEqualTo(620);
        assertThat(merged.province()).isEqualTo("浙江");
        assertThat(merged.subjectGroup()).isEqualTo("物理类");
    }
}
