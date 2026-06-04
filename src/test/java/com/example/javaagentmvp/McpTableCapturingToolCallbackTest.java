package com.example.javaagentmvp;

import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTableCapturingToolCallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpTableExtractor extractor = new McpTableExtractor(objectMapper);

    @AfterEach
    void tearDown() {
        McpTableContext.clear();
    }

    @Test
    void getMajorByScoreCapturesReachSteadySafeTablesInOrder() {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("opstream_agent_admission_score_getMajorByScore")
                        .description("test")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                try {
                    calls.incrementAndGet();
                    int score = objectMapper.readTree(toolInput).get("score").intValue();
                    String majorName = switch (score) {
                        case 645 -> "冲高专业";
                        case 630 -> "稳妥专业";
                        case 615 -> "保底专业";
                        default -> "其他";
                    };
                    return objectMapper.writeValueAsString(Map.of(
                            "count", 1,
                            "majors", List.of(Map.of(
                                    "university_name", "合肥工业大学",
                                    "major_name", majorName,
                                    "campus", "合肥校区",
                                    "min_score", String.valueOf(score),
                                    "min_rank", 10000,
                                    "max_score", String.valueOf(score + 3),
                                    "year", 2025,
                                    "subject_group", "物理类",
                                    "admission_type", "普通批"))));
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
        wrapped.call("{\"score\":630,\"province\":\"安徽\",\"subject_group\":\"物理类\"}");

        assertEquals(3, calls.get());
        List<ChatTable> tables = McpTableContext.tables();
        assertEquals(3, tables.size());
        assertTrue(tables.get(0).title().startsWith("冲（645分"));
        assertEquals("冲高专业", tables.get(0).rows().get(0).get("major_name"));
        assertTrue(tables.get(1).title().startsWith("稳（630分"));
        assertEquals("稳妥专业", tables.get(1).rows().get(0).get("major_name"));
        assertTrue(tables.get(2).title().startsWith("保（615分"));
        assertEquals("保底专业", tables.get(2).rows().get(0).get("major_name"));
    }
}
