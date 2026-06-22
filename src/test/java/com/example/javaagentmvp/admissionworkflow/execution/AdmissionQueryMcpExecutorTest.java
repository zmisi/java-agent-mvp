package com.example.javaagentmvp.admissionworkflow.execution;

import com.example.javaagentmvp.McpTableCapturingToolCallback;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionFiltersIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionQueryMcpExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpTableExtractor extractor = new McpTableExtractor(objectMapper);

    @AfterEach
    void tearDown() {
        McpTableContext.clear();
        com.example.javaagentmvp.chat.ui.McpRankContext.clear();
        com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext.clear();
    }

    @Test
    void requiresForcedMcpForCompleteMajorQuery() {
        AdmissionQueryIr query = majorQuery(List.of());
        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                stubTool("getMajorByScore", "{}"),
                stubTool("getMajorByRank", "{}"),
                stubTool("getRankByScore", "{}"),
                objectMapper);
        assertThat(executor.requiresForcedMcp(query)).isTrue();
    }

    @Test
    void skipsWhenNeedsClarification() {
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                AdmissionSlotsIr.empty(),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of("score", "provinces"),
                0.5,
                "长三角",
                null);
        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                stubTool("getMajorByScore", "{}"),
                stubTool("getMajorByRank", "{}"),
                stubTool("getRankByScore", "{}"),
                objectMapper);
        assertThat(executor.requiresForcedMcp(query)).isFalse();
        assertThat(executor.execute(query).attempted()).isFalse();
    }

    @Test
    void fallsBackToScoreTiersWhenRankResultIsSparse() throws Exception {
        AtomicReference<String> capturedScoreInput = new AtomicReference<>();
        ToolCallback rank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getRankByScore").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"count\":1,\"ranks\":[{\"year\":2025,\"province\":\"安徽\",\"subject_group\":\"物理类\","
                        + "\"score\":631,\"rank_min\":9967,\"rank_max\":10374}]}";
            }
        };
        ToolCallback majorByRank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getMajorByRank").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"user_rank\":9967,\"tier_counts\":{\"冲\":0,\"稳\":1,\"保\":22},"
                        + "\"majors_by_tier\":{\"冲\":[],\"稳\":[{\"major_name\":\"专项\"}],\"保\":[]},\"majors\":[]}";
            }
        };
        ToolCallback majorByScore = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getMajorByScore").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                capturedScoreInput.set(toolInput);
                return "{\"count\":3,\"majors\":["
                        + "{\"university_name\":\"合肥工业大学\",\"major_name\":\"冲高\",\"min_score\":\"640\"},"
                        + "{\"university_name\":\"合肥工业大学\",\"major_name\":\"稳妥\",\"min_score\":\"625\"},"
                        + "{\"university_name\":\"安徽大学\",\"major_name\":\"保底\",\"min_score\":\"610\"}"
                        + "]}";
            }
        };

        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(631, null, List.of("安徽"), "物理类", null, null),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "631分 安徽 物理类 能报什么专业",
                null);

        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                majorByScore,
                McpTableCapturingToolCallback.wrap(majorByRank, extractor, objectMapper),
                McpTableCapturingToolCallback.wrap(rank, extractor, objectMapper),
                objectMapper);

        AdmissionQueryMcpExecutor.ExecutionResult result = executor.execute(query);

        assertThat(result.success()).isTrue();
        assertThat(capturedScoreInput.get()).contains("\"score\":646");
        JsonNode payload = objectMapper.readTree(result.toolResponse());
        assertThat(payload.path("tier_classification").asText()).isEqualTo("score");
        assertThat(payload.path("tier_counts").path("冲").asInt()).isEqualTo(1);
        assertThat(payload.path("tier_counts").path("稳").asInt()).isEqualTo(1);
        assertThat(payload.path("tier_counts").path("保").asInt()).isEqualTo(1);
        assertThat(McpTableContext.tables()).hasSize(4);
        assertThat(McpTableContext.tables().get(1).title()).startsWith("冲（631分");
    }

    @Test
    void filtersMajorTiersToIncludeSchool() throws Exception {
        ToolCallback rank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getRankByScore").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"count\":1,\"ranks\":[{\"year\":2025,\"province\":\"安徽\",\"subject_group\":\"物理类\","
                        + "\"score\":631,\"rank_min\":9967,\"rank_max\":10374}]}";
            }
        };
        ToolCallback majorByRank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getMajorByRank").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return "{\"user_rank\":9967,"
                        + "\"tier_counts\":{\"冲\":1,\"稳\":2,\"保\":1},"
                        + "\"majors_by_tier\":{"
                        + "\"冲\":[{\"university_name\":\"合肥工业大学\",\"major_name\":\"冲高\"}],"
                        + "\"稳\":["
                        + "{\"university_name\":\"合肥工业大学\",\"major_name\":\"稳妥\"},"
                        + "{\"university_name\":\"安徽大学\",\"major_name\":\"法学\"}],"
                        + "\"保\":[{\"university_name\":\"安徽医科大学\",\"major_name\":\"临床\"}]"
                        + "},\"majors\":[]}";
            }
        };

        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(631, null, List.of("安徽"), "物理类", null, null),
                new AdmissionFiltersIr(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of("合肥工业大学"),
                        List.of(),
                        List.of()),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "631分 安徽 物理类 能报 合肥工业大学 什么专业",
                null);

        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                stubTool("getMajorByScore", "{}"),
                McpTableCapturingToolCallback.wrap(majorByRank, extractor, objectMapper),
                McpTableCapturingToolCallback.wrap(rank, extractor, objectMapper),
                objectMapper,
                extractor,
                hfutRagProperties());

        AdmissionQueryMcpExecutor.ExecutionResult result = executor.execute(query);

        assertThat(result.success()).isTrue();
        JsonNode payload = objectMapper.readTree(result.toolResponse());
        assertThat(payload.path("tier_counts").path("稳").asInt()).isEqualTo(1);
        assertThat(payload.path("majors_by_tier").path("稳").get(0).path("university_name").asText())
                .isEqualTo("合肥工业大学");
        assertThat(payload.path("tier_counts").path("保").asInt()).isEqualTo(0);
    }

    private static RagProperties hfutRagProperties() {
        return new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "",
                4,
                0.7,
                false,
                "",
                new RagProperties.Routing(List.of(), List.of()),
                new RagProperties.Admissions(
                        true,
                        List.of(),
                        4,
                        12,
                        List.of(new RagProperties.School("hfut", "合肥工业大学", List.of("合工大"), List.of())),
                        ""),
                new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }

    @Test
    void executesMajorQueryUsingIrSlots() {
        AtomicReference<String> capturedRankInput = new AtomicReference<>();
        AtomicReference<String> capturedMajorInput = new AtomicReference<>();
        ToolCallback rank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getRankByScore").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                capturedRankInput.set(toolInput);
                return "{\"count\":1,\"ranks\":[{\"year\":2025,\"province\":\"安徽\",\"subject_group\":\"物理类\","
                        + "\"score\":630,\"rank_min\":8000,\"rank_max\":8100}]}";
            }
        };
        ToolCallback majorByRank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getMajorByRank").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                capturedMajorInput.set(toolInput);
                return "{\"count\":1,\"user_rank\":8000,\"majors_by_tier\":{\"冲\":[],\"稳\":[],\"保\":[]},\"majors\":[]}";
            }
        };
        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                stubTool("getMajorByScore", "{}"),
                McpTableCapturingToolCallback.wrap(majorByRank, extractor, objectMapper),
                McpTableCapturingToolCallback.wrap(rank, extractor, objectMapper),
                objectMapper);

        AdmissionQueryMcpExecutor.ExecutionResult result = executor.execute(majorQuery(List.of()));

        assertThat(result.success()).isTrue();
        assertThat(capturedRankInput.get()).contains("\"score\":630");
        assertThat(capturedRankInput.get()).contains("\"province\":\"安徽\"");
        assertThat(capturedMajorInput.get()).contains("\"rank\":8000");
        assertThat(capturedMajorInput.get()).contains("\"province\":\"安徽\"");
        assertThat(capturedMajorInput.get()).contains("\"subject_group\":\"物理类\"");
    }

    @Test
    void executesRankBasedMajorQueryUsingIrSlots() {
        AtomicReference<String> capturedInput = new AtomicReference<>();
        ToolCallback majorByRank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("getMajorByRank")
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
                capturedInput.set(toolInput);
                return "{\"count\":1,\"user_rank\":10000,\"majors_by_tier\":{\"冲\":[],\"稳\":[],\"保\":[]},\"majors\":[]}";
            }
        };

        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(null, 10000, List.of("安徽"), "物理类", 2025, "普通批"),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "排名10000名 安徽 物理类 能报什么专业",
                null);

        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                stubTool("getMajorByScore", "{}"),
                McpTableCapturingToolCallback.wrap(majorByRank, extractor, objectMapper),
                stubTool("getRankByScore", "{}"),
                objectMapper);

        AdmissionQueryMcpExecutor.ExecutionResult result = executor.execute(query);

        assertThat(result.success()).isTrue();
        assertThat(capturedInput.get()).contains("\"rank\":10000");
        assertThat(capturedInput.get()).contains("\"province\":\"安徽\"");
        assertThat(capturedInput.get()).contains("\"subject_group\":\"物理类\"");
    }

    private static AdmissionQueryIr majorQuery(List<String> needsClarification) {
        return new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(630, null, List.of("安徽"), "物理类", 2025, "普通批"),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                needsClarification,
                0.9,
                "安徽630分物理类",
                null);
    }

    @Test
    void executesRankQueryAcrossMultipleProvinces() {
        AtomicReference<String> capturedInput = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ToolCallback rank = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("getRankByScore").description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                capturedInput.set(toolInput);
                calls.incrementAndGet();
                try {
                    String province = objectMapper.readTree(toolInput).path("province").asText("江苏");
                    return objectMapper.writeValueAsString(Map.of(
                            "count", 1,
                            "ranks", List.of(Map.of(
                                    "province", province,
                                    "subject_group", "历史类",
                                    "year", 2025,
                                    "rank_min", 1000,
                                    "rank_max", 1100))));
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_rank",
                new AdmissionSlotsIr(600, null, List.of("江苏", "浙江", "上海"), null, null, null),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "600分长三角排名",
                null);

        AdmissionQueryMcpExecutor executor = new AdmissionQueryMcpExecutor(
                stubTool("getMajorByScore", "{}"),
                stubTool("getMajorByRank", "{}"),
                McpTableCapturingToolCallback.wrap(rank, extractor, objectMapper),
                objectMapper);

        AdmissionQueryMcpExecutor.ExecutionResult result = executor.execute(query);

        assertThat(result.success()).isTrue();
        assertThat(calls.get()).isEqualTo(3);
        assertThat(com.example.javaagentmvp.chat.ui.McpRankContext.captures()).hasSize(3);
        com.example.javaagentmvp.chat.ui.McpRankContext.clear();
    }

    private ToolCallback tieredMajorDelegate(AtomicReference<String> capturedInput) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("getMajorByScore")
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
                capturedInput.set(toolInput);
                try {
                    Map<String, Object> major = new LinkedHashMap<>();
                    major.put("major_name", "计算机");
                    major.put("min_score", 625);
                    Map<String, Object> root = Map.of(
                            "count", 1,
                            "majors", List.of(major));
                    return objectMapper.writeValueAsString(root);
                }
                catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static ToolCallback stubTool(String name, String response) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("test").inputSchema("{}").build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String toolInput) {
                return response;
            }
        };
    }
}
