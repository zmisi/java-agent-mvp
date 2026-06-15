package com.example.javaagentmvp.rag;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
import com.example.javaagentmvp.admissionworkflow.intent.SlotDelta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryRouterTest {

    private static final List<String> RAG_PATTERNS = List.of(
            "\\brag\\b",
            "spring\\s*ai|questionansweradvisor|simplevectorstore|embedding|vector\\s*store",
            "微调|知识库|向量|检索增强|文档问答",
            "招生简章|招生章程|录取规则|招生政策|录取办法|投档规则|体检要求|加分政策|转专业|专项计划|振兴计划|高校专项|国家专项|地方专项|中外合作|专升本|招生计划");

    private static final List<String> DATABASE_PATTERNS = List.of(
            "\\b(list|show|describe|desc)\\b[\\s\\S]{0,40}\\btables?\\b|\\btables?\\b[\\s\\S]{0,20}\\b(list|show|all)\\b",
            "\\b(information_schema|table_schema|table_name|column_name)\\b",
            "\\b(schema|column|columns|row|rows|record|records)\\b",
            "\\b(select|insert|update|delete|from|where|join|group by|order by)\\b",
            "\\b(employee|department|salary|title|postgres|postgresql|sql|database|db)\\b",
            "\\b(how many|count|aggregate|sum|avg|max|min)\\b",
            "数据库|数据表|表结构|表名|列名|员工|部门|薪资|统计",
            "\\d{3,4}\\s*分[\\s\\S]{0,60}(专业|报考|报志愿|志愿|可报|能上|录取|哪些|什么|报什么|能上什么)",
            "(专业|报考|报志愿|志愿|可报|能上|录取)[\\s\\S]{0,60}\\d{3,4}\\s*分",
            "(多少|几分|考了|高考)\\s*\\d{3,4}\\s*分[\\s\\S]{0,40}(专业|学校|院校|报|志愿)");

    private final ConversationTurnResolver turnResolver = new ConversationTurnResolver();

    private final RagQueryRouter router = new RagQueryRouter(testProperties(), turnResolver);

    private static RagProperties testProperties() {
        return new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "classpath:/rag-docs/**/*.md",
                4,
                0.70,
                true,
                "",
                new RagProperties.Routing(RAG_PATTERNS, DATABASE_PATTERNS),
                new RagProperties.Admissions(
                        true,
                        java.util.List.of("招生简章", "招生章程", "章程", "简章", "政策", "规则", "专项", "转专业", "体检", "投档", "招生计划"),
                        4,
                        12,
                        java.util.List.of(
                                new RagProperties.School(
                                        "hfut",
                                        "合肥工业大学",
                                        java.util.List.of("合工大", "合肥工业大学", "HFUT"),
                                        java.util.List.of("/hfut/", "hfut/")),
                                new RagProperties.School(
                                        "hfuu",
                                        "合肥大学",
                                        java.util.List.of("合肥大学", "HFUU"),
                                        java.util.List.of("/hfuu/", "hfuu/"))),
                        ""),
                new RagProperties.Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }

    @Test
    void skipsDatabaseTableListing() {
        RagQueryRouter.Decision decision = router.decide("list all tables");
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void usesRagForDocQuestions() {
        RagQueryRouter.Decision decision = router.decide("RAG 和微调有什么区别？");
        assertTrue(decision.useRag());
    }

    @Test
    void skipsRagForScoreRankQueries() {
        RagQueryRouter.Decision decision = router.decide("安徽物理类620分排名多少");
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void skipsRagForScoreWithoutFenSuffix() {
        RagQueryRouter.Decision decision = router.decide("安徽 2025 物理 普通批 630 可以报考什么专业？");
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void skipsRagForScoreMajorQueries() {
        RagQueryRouter.Decision decision = router.decide("安徽考生630分可以报考合工大什么专业");
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void usesRagForResolvedPolicyIntent() {
        RagQueryRouter.Decision decision = router.decide(
                new ResolvedTurn(
                        AdmissionIntent.POLICY,
                        AdmissionInputParser.parse("合工大转专业政策"),
                        SlotDelta.NONE,
                        false),
                "合工大转专业政策");
        assertTrue(decision.useRag());
    }

    @Test
    void usesRagForAdmissionsBrochureQuestions() {
        RagQueryRouter.Decision decision = router.decide("合工大2025年招生章程有哪些录取规则");
        assertTrue(decision.useRag());
    }

    @Test
    void skipsRagForScoreQueryFollowUp() {
        RagQueryRouter.Decision decision = router.decide(
                "安徽，物理类， 2025， 普通批",
                List.of("630分可以报考什么专业"),
                List.of("请提供您的所在省份和科类（物理类/历史类），以便查询630分可报考的专业。"));
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void skipsRagForRankQueryProvinceFollowUp() {
        RagQueryRouter.Decision decision = router.decide(
                "浙江呢？",
                List.of("600分在安徽省的排名"),
                List.of("rank-result-table 位次 一分一段"));
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void skipsRagForRankQueryScoreFollowUp() {
        RagQueryRouter.Decision decision = router.decide(
                "620分呢？",
                List.of("600分在安徽省的排名"),
                List.of("rank-result-table"));
        assertFalse(decision.useRag());
        assertFalse(decision.shouldRetrieve());
    }

    @Test
    void usesRagWhenFollowUpIsBrochureNotScoreParams() {
        RagQueryRouter.Decision decision = router.decide(
                "合工大2025年招生章程",
                List.of("630分可以报考什么专业"),
                List.of("请提供您的所在省份和科类"));
        assertTrue(decision.useRag());
    }

    @Test
    void skipsWeakRetrievalScores() {
        RagQueryRouter.Decision preliminary = router.decide("hello there");
        assertTrue(preliminary.shouldRetrieve());

        var weak = java.util.List.of(
                new org.springframework.ai.document.Document("x", java.util.Map.of("distance", 0.80)));
        RagQueryRouter.Decision finalDecision = router.afterRetrieval(preliminary, weak);
        assertFalse(finalDecision.useRag());
    }
}
