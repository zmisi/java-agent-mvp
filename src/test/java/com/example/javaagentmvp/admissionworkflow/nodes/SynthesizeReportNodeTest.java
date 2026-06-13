package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.SynthesisProperties;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.rag.RagSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SynthesizeReportNodeTest {

    @Mock
    private ChatClient workflowChatClient;

    @Mock
    private ChatClientRequestSpec requestSpec;

    @Mock
    private CallResponseSpec responseSpec;

    private SynthesizeReportNode node;

    @BeforeEach
    void setUp() {
        AdmissionWorkflowProperties properties = new AdmissionWorkflowProperties(
                true,
                "admission_report",
                false,
                new SynthesisProperties(true, "classpath:prompts/admission-report-synthesis.md", 0.3, true));
        node = new SynthesizeReportNode(
                workflowChatClient,
                properties,
                new DefaultResourceLoader());
    }

    @Test
    void synthesizesReportAndWritesAssistantField() {
        when(workflowChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn("## 概况\n匹配 1 条专业。");

        WorkflowContext context = new WorkflowContext("run-1", "安徽630分合工大计算机政策");
        context.put(IntentClassifyNode.KEY_INTENT, AdmissionIntent.REPORT);
        Map<String, Object> finalResult = new LinkedHashMap<>();
        finalResult.put("intent", AdmissionIntent.REPORT.name());
        finalResult.put("summary", "综合报告：匹配 1 条");
        finalResult.put("scoreResult", Map.of("count", 1, "tier_counts", Map.of("冲", 0, "稳", 1, "保", 0)));
        finalResult.put("policySources", List.of(new RagSource("2025章程", "hfut/charter.md", "snippet", "hfut")));
        context.put(FormatResponseNode.KEY_FINAL_RESULT, finalResult);

        WorkflowNodeResult result = node.execute(context);

        assertThat(result.status().name()).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> updated = context.get(FormatResponseNode.KEY_FINAL_RESULT, Map.class);
        assertThat(updated.get("report")).isEqualTo("## 概况\n匹配 1 条专业。");
        assertThat(updated.get("assistant")).isEqualTo("## 概况\n匹配 1 条专业。");
        assertThat(updated.get("summary")).isEqualTo("综合报告：匹配 1 条");
        verify(workflowChatClient).prompt();
    }

    @Test
    void fallsBackToSummaryWhenLlmFails() {
        when(workflowChatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("model unavailable"));

        WorkflowContext context = new WorkflowContext("run-2", "安徽630分");
        context.put(IntentClassifyNode.KEY_INTENT, AdmissionIntent.SCORE);
        Map<String, Object> finalResult = new LinkedHashMap<>();
        finalResult.put("summary", "已查询到 3 个可报专业");
        context.put(FormatResponseNode.KEY_FINAL_RESULT, finalResult);

        WorkflowNodeResult result = node.execute(context);

        assertThat(result.status().name()).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> updated = context.get(FormatResponseNode.KEY_FINAL_RESULT, Map.class);
        assertThat(updated.get("report")).isEqualTo("已查询到 3 个可报专业");
        assertThat(updated.get("assistant")).isEqualTo("已查询到 3 个可报专业");
    }

    @Test
    void buildUserPromptIncludesStructuredSections() {
        WorkflowContext context = new WorkflowContext("run-3", "安徽630分合工大计算机政策");
        Map<String, Object> finalResult = Map.of(
                "intent", "REPORT",
                "summary", "综合报告",
                "scoreResult", Map.of(
                        "count", 1,
                        "tier_counts", Map.of("冲", 0, "稳", 1, "保", 0),
                        "majors", List.of(Map.of(
                                "university_name", "合肥工业大学",
                                "major_name", "软件工程",
                                "min_score", "622"))),
                "policySources", List.of(new RagSource("章程", "hfut/charter.md", "转专业规则", "hfut")));

        String prompt = SynthesizeReportNode.buildUserPrompt(context, finalResult);

        assertThat(prompt).contains("用户问题");
        assertThat(prompt).contains("冲/稳/保数量");
        assertThat(prompt).contains("合肥工业大学");
        assertThat(prompt).contains("转专业规则");
    }
}
