package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionFiltersIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionSlotsIr;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.tool.AdmissionScoreToolClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScoreToolNodeTest {

    @Mock
    private AdmissionScoreToolClient admissionScoreToolClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScoreToolNode scoreToolNode;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        scoreToolNode = new ScoreToolNode(admissionScoreToolClient, objectMapper);
    }

    @Test
    void rankQueryFansOutAcrossMultipleProvinces() {
        when(admissionScoreToolClient.getRankByScore(anyString(), anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> rankResult(
                        invocation.getArgument(2, String.class),
                        "历史类"));

        WorkflowContext context = rankContext(
                "run-1",
                new AdmissionSlotsIr(600, null, List.of("江苏", "浙江", "上海"), null, null, null));

        WorkflowNodeResult result = scoreToolNode.execute(context);

        assertThat(result.status().name()).isEqualTo("SUCCEEDED");
        verify(admissionScoreToolClient, times(3)).getRankByScore(anyString(), anyInt(), any(), any(), any());
        JsonNode rankResult = context.get(ScoreToolNode.KEY_RANK_RESULT, JsonNode.class);
        assertThat(rankResult.path("count").asInt()).isEqualTo(3);
        assertThat(rankResult.path("ranks")).hasSize(3);
        @SuppressWarnings("unchecked")
        List<String> provinces = (List<String>) result.output().get("provinces");
        assertThat(provinces).containsExactly("江苏", "浙江", "上海");
    }

    @Test
    void rankQueryUsesSingleCallWhenNoProvincesInIr() {
        when(admissionScoreToolClient.getRankByScore(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(rankResult("安徽", "物理类"));

        WorkflowContext context = rankContext(
                "run-2",
                new AdmissionSlotsIr(620, null, List.of(), "物理类", 2025, null));

        WorkflowNodeResult result = scoreToolNode.execute(context);

        assertThat(result.status().name()).isEqualTo("SUCCEEDED");
        verify(admissionScoreToolClient).getRankByScore("run-2", 620, null, "物理类", 2025);
    }

    private WorkflowContext rankContext(String runId, AdmissionSlotsIr slots) {
        WorkflowContext context = new WorkflowContext(runId, "rank query");
        context.put(CompileQueryNode.KEY_INTENT, AdmissionIntent.RANK);
        context.put(CompileQueryNode.KEY_ADMISSION_QUERY, new AdmissionQueryIr(
                "search_rank",
                slots,
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.9,
                "rank query",
                null));
        return context;
    }

    private ObjectNode rankResult(String province, String subjectGroup) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode ranks = objectMapper.createArrayNode();
        ObjectNode row = objectMapper.createObjectNode();
        row.put("province", province == null ? "安徽" : province);
        row.put("subject_group", subjectGroup);
        row.put("year", 2025);
        row.put("rank_min", 1000);
        row.put("rank_max", 1100);
        ranks.add(row);
        root.put("count", 1);
        root.set("ranks", ranks);
        return root;
    }
}
