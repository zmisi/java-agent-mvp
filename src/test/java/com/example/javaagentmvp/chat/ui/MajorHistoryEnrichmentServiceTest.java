package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MajorHistoryEnrichmentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parseProvinceFromTitleSkipsTierAndRankSegments() {
        assertEquals("安徽", MajorHistoryEnrichmentService.parseProvinceFromTitle(
                "冲（排名50000名 · 安徽 · 2025 · 物理类 · 普通批）"));
        assertEquals("安徽", MajorHistoryEnrichmentService.parseProvinceFromTitle(
                "稳（630分 · 安徽 · 2025 · 物理类 · 普通批）"));
        assertNull(MajorHistoryEnrichmentService.parseProvinceFromTitle("冲（排名50000名 · 2025 · 物理类 · 普通批）"));
    }

    @Test
    void enrichWithHistoryExpandsGroupedMajorsForDefaultYear() {
        Map<String, String> major = new LinkedHashMap<>();
        major.put("major_name", "数字媒体技术");
        major.put("plan_count", "68");
        major.put("campus", "校本部");
        major.put("min_score", "574");
        major.put("min_rank", "47082");
        major.put("max_score", "594");
        major.put("year", "2025");
        major.put("subject_group", "物理类");
        major.put("admission_type", "普通批");

        ChatTableGroup group = new ChatTableGroup("AUST", "安徽理工大学", 1, "574", List.of(major));
        ChatTable table = new ChatTable(
                "稳（572分 · 安徽 · 2025 · 物理类 · 普通批）",
                List.of(new ChatTableColumn("major_name", "专业")),
                List.of(),
                List.of(group),
                "安徽");

        MajorHistoryMcpClient client = MajorHistoryMcpClient.forTest(stubHistoryTool(), objectMapper);
        MajorHistoryEnrichmentService service = new MajorHistoryEnrichmentService(client);

        List<ChatTable> enriched = service.enrichWithHistory(List.of(table));

        assertEquals(1, enriched.size());
        assertEquals(3, enriched.get(0).groups().get(0).majors().size());
        assertEquals(1, enriched.get(0).groups().get(0).majorCount());
        assertEquals("数字媒体技术", enriched.get(0).groups().get(0).majors().get(0).get("major_name"));
        assertEquals("2025", enriched.get(0).groups().get(0).majors().get(0).get("year"));
        assertEquals("-", enriched.get(0).groups().get(0).majors().get(1).get("major_name"));
        assertEquals("2024", enriched.get(0).groups().get(0).majors().get(1).get("year"));
        assertTrue(MajorHistoryTableExpander.isHistoryRow(enriched.get(0).groups().get(0).majors().get(1)));
    }

    private ToolCallback stubHistoryTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("getMajorHistory")
                        .description("test")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return """
                        {
                          "count": 2,
                          "rows": [
                            {
                              "lookup_university_code": "AUST",
                              "lookup_major_name": "数字媒体技术",
                              "year": 2024,
                              "plan_count": 65,
                              "campus": "校本部",
                              "min_score": "568",
                              "min_rank": 49000,
                              "max_score": "588",
                              "subject_group": "物理类",
                              "admission_type": "普通批"
                            },
                            {
                              "lookup_university_code": "AUST",
                              "lookup_major_name": "数字媒体技术",
                              "year": 2023,
                              "plan_count": 62,
                              "campus": "校本部",
                              "min_score": "555",
                              "min_rank": 54000,
                              "max_score": "575",
                              "subject_group": "物理类",
                              "admission_type": "普通批"
                            }
                          ]
                        }
                        """;
            }
        };
    }
}
