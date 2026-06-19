package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryCompileService;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.ClarificationSupport;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CompileQueryNode implements WorkflowNode {

    public static final String NAME = "compile_query";
    public static final String KEY_ADMISSION_QUERY = "admissionQuery";
    public static final String KEY_CLARIFICATION_MESSAGE = "clarificationMessage";

    private final AdmissionQueryCompileService admissionQueryCompileService;

    public CompileQueryNode(AdmissionQueryCompileService admissionQueryCompileService) {
        this.admissionQueryCompileService = admissionQueryCompileService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        AdmissionQueryIr query = admissionQueryCompileService.compile(context.inputMessage());
        context.put(KEY_ADMISSION_QUERY, query);

        AdmissionIntent intent = query.toIntent();
        context.put(IntentClassifyNode.KEY_INTENT, intent);

        if (query.blocksMcpExecution()) {
            String clarification = ClarificationSupport.buildMessage(query.needsClarification());
            context.put(KEY_CLARIFICATION_MESSAGE, clarification);
            return WorkflowNodeResult.succeeded(Map.of(
                    "task", query.task(),
                    "intent", intent.name(),
                    "needsClarification", query.needsClarification(),
                    "clarificationMessage", clarification,
                    "source", query.parseTrace() == null ? "unknown" : query.parseTrace().llmUsed()
                            ? "remote_llm" : "compiler"));
        }

        return WorkflowNodeResult.succeeded(Map.of(
                "task", query.task(),
                "intent", intent.name(),
                "provinces", query.slots().provincesOrEmpty(),
                "preferences", query.preferences().size(),
                "confidence", query.confidence()));
    }
}
