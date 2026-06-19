package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTableExtractorTest {

    private final McpTableExtractor extractor = new McpTableExtractor(new ObjectMapper());

    @Test
    void extractMajorByScoreBuildsTierTitleWithUserScore() throws Exception {
        String toolInput = """
                {"score":630,"province":"安徽","year":2025,"subject_group":"物理类","admission_type":"普通批"}
                """;
        var majors = new ObjectMapper().createArrayNode();

        ChatTable table = extractor.extractMajorByScoreFromMajors(toolInput, majors, "冲").orElseThrow();

        assertEquals("冲（630分 · 安徽 · 2025 · 物理类 · 普通批）", table.title());
        assertTrue(table.rows().isEmpty());
        assertTrue(table.groups().isEmpty());
    }

    @Test
    void extractMajorByScoreBuildsTableWithChineseColumns() {
        String toolInput = """
                {"score":630,"province":"安徽","year":2025,"subject_group":"物理类","admission_type":"普通批"}
                """;
        String responseData = """
                {
                  "count": 2,
                  "majors": [
                    {
                      "university_name": "合肥工业大学",
                      "major_name": "能源与动力工程",
                      "campus": "合肥校区",
                      "min_score": "630.00",
                      "min_rank": 10491,
                      "max_score": "635.00",
                      "year": 2025,
                      "subject_group": "物理类",
                      "admission_type": "普通批"
                    },
                    {
                      "university_name": "合肥工业大学",
                      "major_name": "软件工程",
                      "campus": "宣城校区",
                      "min_score": "628.00",
                      "min_rank": null,
                      "max_score": null,
                      "year": 2025,
                      "subject_group": "物理类",
                      "admission_type": "普通批"
                    }
                  ]
                }
                """;

        ChatTable table = extractor.extract("getMajorByScore", toolInput, responseData).orElseThrow();

        assertEquals("可报专业（630分 · 安徽 · 2025 · 物理类 · 普通批）", table.title());
        assertEquals(2, table.rows().size());
        assertEquals("能源与动力工程", table.rows().get(0).get("major_name"));
        assertEquals("630", table.rows().get(0).get("min_score"));
        assertEquals("10491", table.rows().get(0).get("min_rank"));
        assertEquals("-", table.rows().get(1).get("min_rank"));
        assertEquals(1, table.groups().size());
        assertEquals("合肥工业大学", table.groups().get(0).universityName());
        assertEquals(2, table.groups().get(0).majorCount());
        assertEquals("628", table.groups().get(0).minScore());
    }

    @Test
    void extractMajorByScoreBuildsUniversityGroupsForMultipleSchools() {
        String toolInput = """
                {"score":600,"province":"安徽","year":2025,"subject_group":"物理类","admission_type":"普通批"}
                """;
        String responseData = """
                {
                  "count": 3,
                  "majors": [
                    {
                      "university_code": "AHU",
                      "university_name": "安徽大学",
                      "major_name": "法学",
                      "campus": "-",
                      "min_score": "615",
                      "min_rank": 12000,
                      "max_score": null,
                      "year": 2025,
                      "subject_group": "物理类",
                      "admission_type": "普通批"
                    },
                    {
                      "university_code": "HFUT",
                      "university_name": "合肥工业大学",
                      "major_name": "软件工程",
                      "campus": "宣城校区",
                      "min_score": "602",
                      "min_rank": 15000,
                      "max_score": null,
                      "year": 2025,
                      "subject_group": "物理类",
                      "admission_type": "普通批"
                    },
                    {
                      "university_code": "HFUT",
                      "university_name": "合肥工业大学",
                      "major_name": "土木工程",
                      "campus": "合肥校区",
                      "min_score": "601",
                      "min_rank": 16000,
                      "max_score": null,
                      "year": 2025,
                      "subject_group": "物理类",
                      "admission_type": "普通批"
                    }
                  ]
                }
                """;

        ChatTable table = extractor.extract("getMajorByScore", toolInput, responseData, "冲").orElseThrow();

        assertEquals(2, table.groups().size());
        assertEquals("安徽大学", table.groups().get(0).universityName());
        assertEquals("AHU", table.groups().get(0).universityCode());
        assertEquals("615", table.groups().get(0).minScore());
        assertEquals("合肥工业大学", table.groups().get(1).universityName());
        assertEquals(2, table.groups().get(1).majorCount());
        assertFalse(table.groups().get(0).majors().get(0).containsKey("university_name"));
    }

    @Test
    void extractUnwrapsMcpTextContentBlocks() throws Exception {
        String innerJson = """
                {"count":1,"majors":[{"university_name":"合肥工业大学","major_name":"能源与动力工程","campus":"合肥校区","min_score":"630.00","min_rank":null,"max_score":"634.00","year":2025,"subject_group":"物理类","admission_type":"普通批"}]}
                """.strip();
        ObjectMapper om = new ObjectMapper();
        String wrapped = om.writeValueAsString(List.of(java.util.Map.of("text", innerJson)));
        ChatTable table = extractor.extract(
                "opstream_agent_admission_score_getMajorByScore",
                "{\"score\":630,\"province\":\"安徽\",\"year\":2025,\"subject_group\":\"物理类\",\"admission_type\":\"普通批\"}",
                wrapped).orElseThrow();
        assertEquals("能源与动力工程", table.rows().get(0).get("major_name"));
        assertEquals("630", table.rows().get(0).get("min_score"));
    }

    @Test
    void extractMatchesPrefixedSpringAiToolName() {
        String responseData = """
                {"count":1,"majors":[{"university_name":"合肥大学","major_name":"软件工程","campus":"-","min_score":"628","min_rank":12000,"max_score":"633","year":2025,"subject_group":"物理类","admission_type":"普通批"}]}
                """;
        ChatTable table = extractor.extract(
                "admission-score-mcp_getMajorByScore",
                "{\"score\":630,\"province\":\"安徽\"}",
                responseData).orElseThrow();
        assertEquals("软件工程", table.rows().get(0).get("major_name"));
    }

    @Test
    void extractRankByScoreUsesProvinceAsTableTitle() throws Exception {
        String responseData = """
                {
                  "count": 1,
                  "ranks": [
                    {
                      "province": "江苏",
                      "year": 2025,
                      "subject_group": "物理类",
                      "score": 600,
                      "rank_min": 33986,
                      "rank_max": 34888,
                      "segment_count": 903,
                      "source_url": "https://example.com"
                    }
                  ]
                }
                """;

        ChatTable table = extractor.extractRankByScore(
                new ObjectMapper().readTree(responseData),
                600,
                "江苏").orElseThrow();

        assertEquals("江苏", table.title());
        assertEquals("江苏", table.province());
        assertEquals("物理类", table.rows().get(0).get("subject_group"));
    }

    @Test
    void extractReturnsEmptyForUnknownTool() {
        assertTrue(extractor.extract("unknownTool", "{}", "{}").isEmpty());
    }

    @Test
    void extractReturnsEmptyWhenMajorsMissing() {
        assertFalse(extractor.extract("getMajorByScore", "{}", "{\"count\":0,\"majors\":[]}").isPresent());
    }

    @Test
    void extractFromToolResponsesAggregatesMultipleTools() {
        List<McpTableExtractor.ToolResponsePayload> responses = List.of(
                new McpTableExtractor.ToolResponsePayload(
                        "getMajorByScore",
                        "{\"score\":630,\"province\":\"安徽\"}",
                        """
                        {"count":1,"majors":[{"university_name":"合肥工业大学","major_name":"软件工程","campus":"宣城校区","min_score":"628","min_rank":12000,"max_score":"633","year":2025,"subject_group":"物理类","admission_type":"普通批"}]}
                        """));

        List<ChatTable> tables = extractor.extractFromToolResponses(responses);

        assertEquals(1, tables.size());
        assertEquals("软件工程", tables.get(0).rows().get(0).get("major_name"));
    }
}
