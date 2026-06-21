package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionCompilerProperties;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionOntologyRegistry;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPriorSlotsBuilder;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.IntentServiceClient;
import com.example.javaagentmvp.admissionworkflow.compiler.LocalAdmissionQueryCompiler;
import com.example.javaagentmvp.admissionworkflow.compiler.UnsupportedConstraintRecorder;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.chat.ConversationPriorUserMessagesResolver;
import com.example.javaagentmvp.chat.PostgresChatMemory;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompileQueryNodeTest {

    @Mock
    private PostgresChatMemory postgresChatMemory;

    private CompileQueryNode compileQueryNode;

    @BeforeEach
    void setUp() throws Exception {
        AdmissionOntologyRegistry ontologyRegistry = new AdmissionOntologyRegistry();
        ontologyRegistry.load();
        AdmissionPriorSlotsBuilder priorSlotsBuilder = new AdmissionPriorSlotsBuilder(ontologyRegistry);
        LocalAdmissionQueryCompiler localCompiler = new LocalAdmissionQueryCompiler(
                ontologyRegistry,
                priorSlotsBuilder,
                new RagQueryRouter(minimalRagProperties()));
        @SuppressWarnings("unchecked")
        ObjectProvider<UnsupportedConstraintRecorder> recorderProvider = mock(ObjectProvider.class);
        AdmissionQueryCompileService compileService = new AdmissionQueryCompileService(
                AdmissionCompilerProperties.defaults(),
                new IntentServiceClient(AdmissionCompilerProperties.defaults(), RestClient.builder()),
                localCompiler,
                priorSlotsBuilder);
        compileQueryNode = new CompileQueryNode(
                compileService,
                new ConversationPriorUserMessagesResolver(postgresChatMemory),
                recorderProvider);
    }

    @Test
    void compilesWithoutPriorMessagesWhenConversationMissing() {
        WorkflowContext context = new WorkflowContext("run-1", "安徽620分物理类");

        WorkflowNodeResult result = compileQueryNode.execute(context);

        AdmissionQueryIr query = context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class);
        assertThat(query.toIntent()).isEqualTo(AdmissionIntent.SCORE);
        assertThat(query.slots().score()).isEqualTo(620);
        assertThat(result.output()).containsEntry("priorTurns", 0);
    }

    @Test
    void mergesPriorTurnsFromChatMemory() {
        when(postgresChatMemory.get(eq("conv-1"))).thenReturn(List.of(
                new UserMessage("安徽考生630分物理类"),
                new UserMessage("那浙江呢？")));
        WorkflowContext context = new WorkflowContext("run-2", "conv-1", "那浙江呢？");

        WorkflowNodeResult result = compileQueryNode.execute(context);

        AdmissionQueryIr query = context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class);
        assertThat(query.slots().score()).isEqualTo(630);
        assertThat(query.slots().subjectGroup()).isEqualTo("物理类");
        assertThat(query.slots().provincesOrEmpty()).containsExactly("浙江");
        assertThat(result.output()).containsEntry("priorTurns", 1);
    }

    @Test
    void ignoresBlankConversationId() {
        WorkflowContext context = new WorkflowContext("run-3", "", "620分");

        compileQueryNode.execute(context);

        assertThat(context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class).slots().score())
                .isEqualTo(620);
    }

    private static RagProperties minimalRagProperties() {
        return new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "classpath:/rag-docs/**/*.md",
                4,
                0.7,
                true,
                "",
                new RagProperties.Routing(
                        List.of("招生简章|招生章程", "\\brag\\b", "微调|知识库"),
                        List.of("\\d{3,4}\\s*分")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "政策", "招生计划"),
                        4,
                        12,
                        List.of(),
                        ""),
                new RagProperties.Hybrid(false, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }
}
