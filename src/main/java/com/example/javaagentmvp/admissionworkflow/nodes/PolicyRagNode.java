package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.PolicyKeywordSupport;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.admissionworkflow.policy.PolicySourceSelector;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagRetrievalService;
import com.example.javaagentmvp.rag.RagSource;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PolicyRagNode implements WorkflowNode {

    public static final String NAME = "policy_rag";
    public static final String KEY_POLICY_SOURCES = "policySources";

    private final ObjectProvider<RagRetrievalService> ragRetrievalService;
    private final RagProperties ragProperties;

    public PolicyRagNode(
            ObjectProvider<RagRetrievalService> ragRetrievalService,
            RagProperties ragProperties) {
        this.ragRetrievalService = ragRetrievalService;
        this.ragProperties = ragProperties;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowContext context) {
        if (context.get(CompileQueryNode.KEY_CLARIFICATION_MESSAGE, String.class) != null) {
            return WorkflowNodeResult.skipped("awaiting clarification");
        }

        AdmissionIntent intent = context.get(CompileQueryNode.KEY_INTENT, AdmissionIntent.class);
        if (intent == AdmissionIntent.SCORE
                && !PolicyKeywordSupport.hasPolicyKeywords(context.inputMessage(), ragProperties)) {
            return WorkflowNodeResult.skipped("score-only intent");
        }
        if (intent == AdmissionIntent.RANK) {
            return WorkflowNodeResult.skipped("rank-only intent");
        }

        RagRetrievalService retrievalService = ragRetrievalService.getIfAvailable();
        if (retrievalService == null) {
            return WorkflowNodeResult.skipped("RAG disabled");
        }

        AdmissionQueryHints.Hints hints = context.get(FilterScoreMajorsNode.KEY_QUERY_HINTS, AdmissionQueryHints.Hints.class);
        if (hints == null) {
            hints = AdmissionQueryHints.parse(context.inputMessage(), ragProperties);
            context.put(FilterScoreMajorsNode.KEY_QUERY_HINTS, hints);
        }

        String retrievalQuery = hints.schoolSpecified() || hints.majorSpecified()
                ? hints.policyRetrievalQuery(context.inputMessage())
                : context.inputMessage();

        List<Document> documents = retrievalService.search(retrievalQuery, List.of(), List.of());
        List<RagSource> sources = PolicySourceSelector.refine(
                documents.stream().map(RagSource::from).toList(),
                hints);
        context.put(KEY_POLICY_SOURCES, sources);

        return WorkflowNodeResult.succeeded(Map.of(
                "sourceCount", sources.size(),
                "retrievalQuery", retrievalQuery,
                "titles", sources.stream().map(RagSource::title).limit(5).toList()));
    }
}
