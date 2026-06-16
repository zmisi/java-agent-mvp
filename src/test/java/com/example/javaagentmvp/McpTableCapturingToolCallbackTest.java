package com.example.javaagentmvp;

import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurnContext;
import com.example.javaagentmvp.admissionworkflow.intent.SlotDelta;
import com.example.javaagentmvp.chat.ui.McpRankContext;
import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpTableCapturingToolCallbackTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpTableExtractor extractor = new McpTableExtractor(objectMapper);

    @AfterEach
    void tearDown() {
        McpTableContext.clear();
        McpRankContext.clear();
    }

    @Test
    void getMajorByScoreClassifiesTiersFromSingleQuery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback delegate = tieredDelegate(calls, new AtomicReference<>(), List.of(
                major("冲高专业", 640),
                major("稳妥专业", 625),
                major("保底专业", 610)));

        ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
        String response = wrapped.call(
                "{\"score\":630,\"province\":\"安徽\",\"year\":2025,\"subject_group\":\"物理类\",\"admission_type\":\"普通批\"}");

        assertEquals(1, calls.get());
        JsonNode parsed = objectMapper.readTree(response);
        assertEquals(630, parsed.get("user_score").intValue());
        assertEquals(1, parsed.get("tier_counts").get("冲").intValue());
        assertEquals(1, parsed.get("tier_counts").get("稳").intValue());
        assertEquals(1, parsed.get("tier_counts").get("保").intValue());

        List<ChatTable> tables = McpTableContext.tables();
        assertEquals(3, tables.size());
        assertTrue(tables.get(0).title().startsWith("冲（630分"));
        assertEquals("冲高专业", tables.get(0).rows().get(0).get("major_name"));
        assertTrue(tables.get(1).title().startsWith("稳（630分"));
        assertEquals("稳妥专业", tables.get(1).rows().get(0).get("major_name"));
        assertTrue(tables.get(2).title().startsWith("保（630分"));
        assertEquals("保底专业", tables.get(2).rows().get(0).get("major_name"));
        assertEquals(1, tables.get(0).groups().size());
        assertEquals(1, tables.get(1).groups().size());
        assertEquals(1, tables.get(2).groups().size());
    }

    @Test
    void overridesLlmScoreWithParsedUserScoreFromToolContext() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Integer> queryScoreUsed = new AtomicReference<>();
        ToolCallback delegate = tieredDelegate(calls, queryScoreUsed, List.of(
                major("冲高专业", 640),
                major("稳妥专业", 625),
                major("保底专业", 610)));

        ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
        ToolContext toolContext = new ToolContext(Map.of(
                ToolContext.TOOL_CALL_HISTORY,
                List.of(new UserMessage("安徽 2025 物理 普通批 630 可以报考什么专业？"))));
        String response = wrapped.call(
                "{\"score\":645,\"province\":\"安徽\",\"year\":2025,\"subject_group\":\"物理类\",\"admission_type\":\"普通批\"}",
                toolContext);

        assertEquals(1, calls.get());
        assertEquals(645, queryScoreUsed.get());
        JsonNode parsed = objectMapper.readTree(response);
        assertEquals(630, parsed.get("user_score").intValue());
        assertEquals(1, parsed.get("tier_counts").get("冲").intValue());
        assertEquals(1, parsed.get("tier_counts").get("稳").intValue());
        assertEquals(1, parsed.get("tier_counts").get("保").intValue());

        List<ChatTable> tables = McpTableContext.tables();
        assertTrue(tables.get(0).title().startsWith("冲（630分"));
    }

    @Test
    void getMajorByScoreHefeiUniversity580AllMajorsAreSafeTier() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Map<String, Object>> hfuuMajors = List.of(
                major("计算机科学与技术", 553),
                major("电子信息工程", 550),
                major("机械设计制造及其自动化", 547),
                major("土木工程", 543),
                major("环境工程", 542),
                major("工业设计", 540),
                major("数学与应用数学（师范）", 540),
                major("无机非金属材料工程", 540),
                major("工商管理", 536),
                major("经济学", 536),
                major("英语", 535));
        ToolCallback delegate = tieredDelegate(calls, new AtomicReference<>(), hfuuMajors);

        ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
        String response = wrapped.call(
                "{\"score\":580,\"province\":\"安徽\",\"year\":2025,\"subject_group\":\"物理类\",\"admission_type\":\"普通批\"}");

        assertEquals(1, calls.get());
        JsonNode parsed = objectMapper.readTree(response);
        assertEquals(0, parsed.get("tier_counts").get("冲").intValue());
        assertEquals(0, parsed.get("tier_counts").get("稳").intValue());
        assertEquals(11, parsed.get("tier_counts").get("保").intValue());

        List<ChatTable> tables = McpTableContext.tables();
        assertEquals(3, tables.size());
        assertTrue(tables.get(0).title().startsWith("冲（580分"));
        assertTrue(tables.get(0).rows().isEmpty());
        assertTrue(tables.get(1).title().startsWith("稳（580分"));
        assertTrue(tables.get(1).rows().isEmpty());
        assertTrue(tables.get(2).title().startsWith("保（580分"));
        assertEquals(11, tables.get(2).rows().size());
        assertTrue(tables.get(0).groups().isEmpty());
        assertTrue(tables.get(1).groups().isEmpty());
        assertEquals(1, tables.get(2).groups().size());
        assertEquals(11, tables.get(2).groups().get(0).majorCount());
    }

    @Test
    void getMajorByScoreSplitsSteadyAndSafeByMinScore() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ToolCallback delegate = tieredDelegate(calls, new AtomicReference<>(), List.of(
                major("计算机科学与技术", 570),
                major("英语", 535)));

        ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
        wrapped.call("{\"score\":580,\"province\":\"安徽\",\"year\":2025,\"subject_group\":\"物理类\"}");

        List<ChatTable> tables = McpTableContext.tables();
        assertTrue(tables.get(0).rows().isEmpty());
        assertEquals("计算机科学与技术", tables.get(1).rows().get(0).get("major_name"));
        assertEquals("英语", tables.get(2).rows().get(0).get("major_name"));
    }

    private ToolCallback tieredDelegate(
            AtomicInteger calls,
            AtomicReference<Integer> queryScoreUsed,
            List<Map<String, Object>> majors) {
        return new ToolCallback() {
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
                    int queryScore = objectMapper.readTree(toolInput).get("score").intValue();
                    queryScoreUsed.set(queryScore);
                    List<Map<String, Object>> filtered = new ArrayList<>();
                    for (Map<String, Object> major : majors) {
                        int minScore = Integer.parseInt(major.get("min_score").toString());
                        if (minScore <= queryScore) {
                            filtered.add(major);
                        }
                    }
                    return objectMapper.writeValueAsString(Map.of("count", filtered.size(), "majors", filtered));
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static Map<String, Object> major(String majorName, int minScore) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("university_code", "HFUT");
        row.put("university_name", "合肥工业大学");
        row.put("major_name", majorName);
        row.put("campus", "合肥校区");
        row.put("min_score", String.valueOf(minScore));
        row.put("min_rank", 10000);
        row.put("max_score", String.valueOf(minScore + 3));
        row.put("year", 2025);
        row.put("province", "安徽");
        row.put("subject_group", "物理类");
        row.put("admission_type", "普通批");
        return row;
    }

    @Test
    void getRankByScoreCapturesDeterministicFormattedReply() throws Exception {
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("getRankByScore")
                        .description("rank")
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
                return "{\"count\":1,\"ranks\":[{\"year\":2025,\"province\":\"安徽\",\"subject_group\":\"历史类\","
                        + "\"score\":600,\"rank_min\":3286,\"rank_max\":3415,\"segment_count\":130,"
                        + "\"source_url\":\"https://example.com/anhui\"}]}";
            }
        };

        ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
        ToolContext toolContext = new ToolContext(Map.of(
                ToolContext.TOOL_CALL_HISTORY,
                List.of(new UserMessage("600分在安徽的排名"))));
        wrapped.call("{\"score\":600,\"province\":\"安徽\"}", toolContext);

        assertTrue(McpRankContext.capture().isPresent());
        String formatted = McpRankContext.capture().orElseThrow().formatted();
        assertTrue(formatted.contains("rank-result-table"));
        assertTrue(formatted.contains("3,286–3,415"));
        assertTrue(formatted.contains("130人"));
        assertTrue(formatted.contains("官方已公布"));
        assertTrue(formatted.contains("rank-source-link"));

        List<ChatTable> tables = McpTableContext.tables();
        assertEquals(1, tables.size());
        ChatTable rankTable = tables.get(0);
        assertEquals("", rankTable.title());
        assertEquals(1, rankTable.rows().size());
        assertEquals("2025年 · 600分", rankTable.rows().get(0).get("year_label"));
        assertEquals("3,286–3,415", rankTable.rows().get(0).get("rank_range"));
    }

    @Test
    void getRankByScoreUsesResolvedTurnContextForSlots() throws Exception {
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("getRankByScore")
                        .description("rank")
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
                return "{\"count\":1,\"ranks\":[{\"year\":2025,\"province\":\"浙江\",\"subject_group\":\"综合类\","
                        + "\"score\":600,\"rank_min\":13472,\"rank_max\":13891,\"segment_count\":420,"
                        + "\"source_url\":\"https://example.com/zhejiang\"}]}";
            }
        };

        ResolvedTurnContext.set(new ResolvedTurn(
                AdmissionIntent.RANK,
                new AdmissionInputParser.ParsedAdmissionInput(600, "浙江", null, null, null),
                SlotDelta.PROVINCE,
                true));
        try {
            ToolCallback wrapped = McpTableCapturingToolCallback.wrap(delegate, extractor, objectMapper);
            wrapped.call("{\"province\": \"浙江\"}", null);

            assertTrue(McpRankContext.capture().isPresent());
            String formatted = McpRankContext.capture().orElseThrow().formatted();
            assertTrue(formatted.contains("浙江"));
            assertTrue(formatted.contains("600分"));
            assertTrue(formatted.contains("13,472–13,891"));
        }
        finally {
            ResolvedTurnContext.clear();
        }
    }
}
