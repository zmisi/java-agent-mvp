package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntentClassifier;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IntentClassifyNode implements WorkflowNode {

    public static final String NAME = "intent_classify";
    public static final String KEY_INTENT = "intent";

    private final AdmissionIntentClassifier intentClassifier;

    public IntentClassifyNode(AdmissionIntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        AdmissionIntent intent = intentClassifier.classify(context.inputMessage());
        context.put(KEY_INTENT, intent);
        return WorkflowNodeResult.succeeded(Map.of(
                "intent", intent.name(),
                "message", context.inputMessage()));
    }
}
