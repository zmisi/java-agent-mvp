package com.example.javaagentmvp.admissionworkflow.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankResponseFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void returnsEmptyMessageWhenNoRanks() throws Exception {
        String markdown = RankResponseFormatter.format(objectMapper.readTree("{\"count\":0,\"ranks\":[]}"), 600, "安徽");
        assertThat(markdown).contains("未能查询到");
    }
}
