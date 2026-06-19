package com.example.javaagentmvp.admissionworkflow.nodes;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowContext;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNode;
import com.example.javaagentmvp.admissionworkflow.engine.WorkflowNodeResult;
import com.example.javaagentmvp.admissionworkflow.planner.QueryPlanner;
import com.example.javaagentmvp.rag.RagRetrievalService;
import com.example.javaagentmvp.rag.RagSource;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PreferenceRagNode implements WorkflowNode {

    public static final String NAME = "preference_rag";
    public static final String KEY_PREFERENCE_SOURCES = "preferenceSources";

    private final ObjectProvider<RagRetrievalService> ragRetrievalService;

    public PreferenceRagNode(ObjectProvider<RagRetrievalService> ragRetrievalService) {
        this.ragRetrievalService = ragRetrievalService;
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

        AdmissionQueryIr query = context.get(CompileQueryNode.KEY_ADMISSION_QUERY, AdmissionQueryIr.class);
        if (query == null || query.preferences().isEmpty()) {
            return WorkflowNodeResult.skipped("no preference constraints");
        }

        RagRetrievalService retrievalService = ragRetrievalService.getIfAvailable();
        if (retrievalService == null) {
            return WorkflowNodeResult.skipped("RAG disabled");
        }

        String retrievalQuery = QueryPlanner.buildPreferenceRetrievalQuery(query);
        if (retrievalQuery.isBlank()) {
            return WorkflowNodeResult.skipped("empty preference retrieval query");
        }

        List<Document> documents = retrievalService.search(retrievalQuery, List.of(), List.of());
        List<RagSource> sources = documents.stream().map(RagSource::from).toList();
        context.put(KEY_PREFERENCE_SOURCES, sources);

        return WorkflowNodeResult.succeeded(Map.of(
                "sourceCount", sources.size(),
                "retrievalQuery", retrievalQuery,
                "dimensions", query.preferences().stream().map(pref -> pref.dimension()).distinct().toList()));
    }
}
