package com.example.javaagentmvp.admissionworkflow.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

class RankResponseFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void formatIntroUsesPlainTextWithoutHtmlTable() throws Exception {
        String json = """
                {
                  "count": 1,
                  "ranks": [
                    {
                      "year": 2025,
                      "province": "安徽",
                      "subject_group": "历史类",
                      "score": 600,
                      "rank_min": 3286,
                      "rank_max": 3415,
                      "segment_count": 130,
                      "source_url": "https://example.com/2025-history"
                    }
                  ]
                }
                """;
        String intro = RankResponseFormatter.formatIntro(objectMapper.readTree(json), 600, "安徽");

        assertThat(intro).contains("安徽 600分");
        assertThat(intro).doesNotContain("rank-result-table");
        assertThat(intro).doesNotContain("<table");
        assertThat(intro).doesNotContain("**");
    }

    @Test
    void formatsConsistentTableAcrossYears() throws Exception {
        String json = """
                {
                  "count": 2,
                  "ranks": [
                    {
                      "year": 2025,
                      "province": "安徽",
                      "subject_group": "历史类",
                      "score": 600,
                      "rank_min": 3286,
                      "rank_max": 3415,
                      "segment_count": 130,
                      "source_url": "https://example.com/2025-history"
                    },
                    {
                      "year": 2024,
                      "province": "安徽",
                      "subject_group": "历史类",
                      "score": 600,
                      "rank_min": 3484,
                      "rank_max": 3626,
                      "segment_count": 143,
                      "source_url": "https://example.com/2024-history"
                    }
                  ]
                }
                """;
        String html = RankResponseFormatter.format(objectMapper.readTree(json), 600, "安徽");

        assertThat(html).contains("600分");
        assertThat(html).contains("rank-result-table");
        assertThat(html).contains("rank-table-header");
        assertThat(html).contains("安徽");
        assertThat(html).contains("<th scope=\"col\">年份</th>");
        assertThat(html).contains("2025年 · 600分");
        assertThat(html).contains("3,286–3,415");
        assertThat(html).contains("130人");
        assertThat(html).contains("2024年 · 600分");
        assertThat(html).contains("3,484–3,626");
        assertThat(html).contains("143人");
        assertThat(html).contains("rank-source-link");
        assertThat(html).contains("✅");
        assertThat(html).doesNotContain("预估");
    }

    @Test
    void formatIntroForMultipleProvinces() {
        String intro = RankResponseFormatter.formatIntroForProvinces(600, List.of("江苏", "浙江", "上海"));
        assertThat(intro).contains("江苏、浙江、上海");
        assertThat(intro).contains("600分");
    }

    @Test
    void formatNoRankDataMessageForRegion() {
        String message = RankResponseFormatter.formatNoRankDataMessage(
                600,
                List.of("东北"),
                List.of("辽宁", "吉林", "黑龙江"));
        assertThat(message).contains("东北");
        assertThat(message).contains("600分");
        assertThat(message).contains("暂未导入");
    }

    @Test
    void formatGroupsMultipleProvincesIntoSeparateSections() throws Exception {
        String json = """
                {
                  "count": 3,
                  "ranks": [
                    {"year": 2025, "province": "江苏", "subject_group": "历史类", "rank_min": 1000, "rank_max": 1100},
                    {"year": 2025, "province": "浙江", "subject_group": "综合类", "rank_min": 2000, "rank_max": 2100},
                    {"year": 2025, "province": "上海", "subject_group": "综合类", "rank_min": 3000, "rank_max": 3100}
                  ]
                }
                """;
        String html = RankResponseFormatter.format(objectMapper.readTree(json), 600, null);

        assertThat(html).contains("江苏、浙江、上海");
        assertThat(html).contains("rank-table-header\">江苏");
        assertThat(html).contains("rank-table-header\">浙江");
        assertThat(html).contains("rank-table-header\">上海");
    }

    @Test
    void returnsEmptyMessageWhenNoRanks() throws Exception {
        String markdown = RankResponseFormatter.format(objectMapper.readTree("{\"count\":0,\"ranks\":[]}"), 600, "安徽");
        assertThat(markdown).contains("未能查询到");
    }
}
