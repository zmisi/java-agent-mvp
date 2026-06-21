package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.ClarificationSupport;
import com.example.javaagentmvp.admissionworkflow.compiler.CompileRecordContext;
import com.example.javaagentmvp.admissionworkflow.compiler.UnsupportedConstraintRecorder;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.chat.ConversationPriorUserMessagesResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CompileQueryNode implements WorkflowNode {

    public static final String NAME = "compile_query";
    public static final String KEY_ADMISSION_QUERY = "admissionQuery";
    public static final String KEY_INTENT = "intent";
    public static final String KEY_CLARIFICATION_MESSAGE = "clarificationMessage";

    private final AdmissionQueryCompileService admissionQueryCompileService;
    private final ConversationPriorUserMessagesResolver priorUserMessagesResolver;
    private final ObjectProvider<UnsupportedConstraintRecorder> unsupportedConstraintRecorder;

    public CompileQueryNode(
            AdmissionQueryCompileService admissionQueryCompileService,
            ConversationPriorUserMessagesResolver priorUserMessagesResolver,
            ObjectProvider<UnsupportedConstraintRecorder> unsupportedConstraintRecorder) {
        this.admissionQueryCompileService = admissionQueryCompileService;
        this.priorUserMessagesResolver = priorUserMessagesResolver;
        this.unsupportedConstraintRecorder = unsupportedConstraintRecorder;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        CompileRecordContext.set(context.conversationId(), null, "workflow", context.inputMessage());
        try {
            List<String> priorUserMessages = priorUserMessagesResolver.resolveNewestFirst(
                    context.conversationId(),
                    context.inputMessage());
            AdmissionQueryIr query = admissionQueryCompileService.compile(
                    context.inputMessage(),
                    priorUserMessages);
            unsupportedConstraintRecorder.ifAvailable(recorder -> recorder.record(query, "workflow"));
            context.put(KEY_ADMISSION_QUERY, query);

            AdmissionIntent intent = query.toIntent();
            context.put(KEY_INTENT, intent);

            if (query.blocksMcpExecution()) {
                String clarification = ClarificationSupport.buildMessage(query.needsClarification());
                context.put(KEY_CLARIFICATION_MESSAGE, clarification);
                return WorkflowNodeResult.succeeded(Map.of(
                        "task", query.task(),
                        "intent", intent.name(),
                        "needsClarification", query.needsClarification(),
                        "clarificationMessage", clarification,
                        "priorTurns", priorUserMessages.size(),
                        "source", query.parseTrace() == null ? "unknown" : query.parseTrace().llmUsed()
                                ? "remote_llm" : "compiler"));
            }

            return WorkflowNodeResult.succeeded(Map.of(
                    "task", query.task(),
                    "intent", intent.name(),
                    "provinces", query.slots().provincesOrEmpty(),
                    "preferences", query.preferences().size(),
                    "priorTurns", priorUserMessages.size(),
                    "confidence", query.confidence()));
        }
        finally {
            CompileRecordContext.clear();
        }
    }
}
