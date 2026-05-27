package com.example.javaagentmvp.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagQueryRouterTest {

    private static final List<String> RAG_PATTERNS = List.of(
            "\\brag\\b",
            "spring\\s*ai|questionansweradvisor|simplevectorstore|embedding|vector\\s*store",
            "微调|知识库|向量|检索增强|文档问答",
            "招生|录取|分数|专业|高考|考生|志愿|合工大|合肥工业大学|安徽");

    private static final List<String> DATABASE_PATTERNS = List.of(
            "\\b(list|show|describe|desc)\\b[\\s\\S]{0,40}\\btables?\\b|\\btables?\\b[\\s\\S]{0,20}\\b(list|show|all)\\b",
            "\\b(information_schema|table_schema|table_name|column_name)\\b",
            "\\b(schema|column|columns|row|rows|record|records)\\b",
            "\\b(select|insert|update|delete|from|where|join|group by|order by)\\b",
            "\\b(employee|department|salary|title|postgres|postgresql|sql|database|db)\\b",
            "\\b(how many|count|aggregate|sum|avg|max|min)\\b",
            "数据库|数据表|表结构|表名|列名|员工|部门|薪资|统计");

    private final RagQueryRouter router = new RagQueryRouter(testProperties());

    private static RagProperties testProperties() {
        return new RagProperties(
                true,
                "classpath:/rag-docs/**/*.md",
                4,
                0.70,
                true,
                "",
                new RagProperties.Routing(RAG_PATTERNS, DATABASE_PATTERNS),
                new RagProperties.Admissions(
                        true,
                        java.util.List.of("招生", "录取", "分数", "分数线", "投档", "专业", "志愿", "高考", "考生"),
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
                        ""));
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
    void usesRagForAdmissionsQuestions() {
        RagQueryRouter.Decision decision = router.decide("安徽考生 625 分 可以上合工大哪些专业");
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
