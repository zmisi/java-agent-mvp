package com.example.javaagentmvp.rag;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.planner.QueryPlanner;
import com.example.javaagentmvp.chat.ChatTurnFlowLog;
import com.example.javaagentmvp.chat.UserTurnContextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Starts the RAG flow: logs {@link RagFlowLogStep#QUESTION} and {@link RagFlowLogStep#RETRIEVE}.
 * when {@link RagQueryRouter} decides the message should use the knowledge base.
 */
public class RagFlowStartAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RagFlowStartAdvisor.class);

    private final RagRetrievalService ragRetrievalService;

    private final RagQueryRouter ragQueryRouter;

    private final int order;

    public RagFlowStartAdvisor(
            RagRetrievalService ragRetrievalService,
            RagQueryRouter ragQueryRouter) {
        this(ragRetrievalService, ragQueryRouter, RagAdvisorOrder.FLOW_START);
    }

    public RagFlowStartAdvisor(
            RagRetrievalService ragRetrievalService,
            RagQueryRouter ragQueryRouter,
            int order) {
        this.ragRetrievalService = ragRetrievalService;
        this.ragQueryRouter = ragQueryRouter;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        RagFlowContext.start();
        String flowId = RagFlowContext.flowId();
        UserTurnContextExtractor.UserTurnContext userTurnContext = UserTurnContextExtractor.extract(request);
        String userMessage = userTurnContext.currentUserMessage();

        AdmissionQueryIr query = AdmissionQueryContext.current()
                .orElse(AdmissionQueryIr.empty(userMessage));

        RagQueryRouter.Decision preliminary = ragQueryRouter.decide(query, userMessage);
        if (!preliminary.useRag() && !preliminary.shouldRetrieve()) {
            RagFlowContext.skip(preliminary.reason());
            log.info("========== RAG skipped [{}] — {} ==========", flowId, preliminary.reason());
            log.info("[RAG {}] user message (MCP path):\n{}", flowId, userMessage);
            log.debug("[RAG {}] routing context — priorUserTurns={}, contextHints={}",
                    flowId, userTurnContext.priorUserMessages().size(), userTurnContext.priorContextHints().size());
            ChatTurnFlowLog.skipped(
                    ChatTurnFlowLog.Step.RAG_RETRIEVE,
                    "reason=%s intent=%s",
                    preliminary.reason(),
                    query.toIntent());
            return chain.nextCall(request);
        }

        log.info("========== RAG flow start [{}] ==========", flowId);
        log.info("[RAG {}] {} 用户问题:\n{}", flowId, RagFlowLogStep.QUESTION, userMessage);

        List<Document> documents = ragRetrievalService.search(
                userMessage,
                userTurnContext.priorUserMessages(),
                userTurnContext.priorContextHints());
        int baseDocCount = documents.size();
        documents = augmentWithPreferenceDocuments(documents, userTurnContext);
        RagQueryRouter.Decision finalDecision = preliminary.useRag()
                ? decisionForForcedRag(documents)
                : ragQueryRouter.afterRetrieval(preliminary, documents);

        if (!finalDecision.useRag()) {
            RagFlowContext.skip(finalDecision.reason());
            log.info("[RAG {}] skipped after retrieval — {}", flowId, finalDecision.reason());
            ChatTurnFlowLog.skipped(
                    ChatTurnFlowLog.Step.RAG_RETRIEVE,
                    "after retrieval reason=%s baseDocs=%d",
                    finalDecision.reason(),
                    baseDocCount);
            return chain.nextCall(request);
        }

        ragRetrievalService.logRetrievedDocuments(flowId, documents);
        RagFlowContext.setSources(documents.stream().map(RagSource::from).toList());
        ChatTurnFlowLog.step(
                ChatTurnFlowLog.Step.RAG_RETRIEVE,
                "useRag=true baseDocs=%d totalDocs=%d sources=%d",
                baseDocCount,
                documents.size(),
                RagFlowContext.sources().size());
        return chain.nextCall(request);
    }

    private List<Document> augmentWithPreferenceDocuments(
            List<Document> documents,
            UserTurnContextExtractor.UserTurnContext userTurnContext) {
        Optional<AdmissionQueryIr> queryOpt = AdmissionQueryContext.current();
        if (queryOpt.isEmpty() || queryOpt.get().preferences().isEmpty()) {
            return documents;
        }
        String preferenceQuery = QueryPlanner.buildPreferenceRetrievalQuery(queryOpt.get());
        if (preferenceQuery.isBlank()) {
            return documents;
        }
        List<Document> preferenceDocs = ragRetrievalService.search(
                preferenceQuery,
                userTurnContext.priorUserMessages(),
                userTurnContext.priorContextHints());
        if (preferenceDocs.isEmpty()) {
            log.info("[RAG {}] preference augment: query={} added=0", RagFlowContext.flowId(), preferenceQuery);
            return documents;
        }
        Map<String, Document> merged = new LinkedHashMap<>();
        for (Document document : documents) {
            merged.put(document.getId(), document);
        }
        for (Document document : preferenceDocs) {
            merged.putIfAbsent(document.getId(), document);
        }
        log.info(
                "[RAG {}] preference augment: query={} added={} total={}",
                RagFlowContext.flowId(),
                preferenceQuery,
                preferenceDocs.size(),
                merged.size());
        return new ArrayList<>(merged.values());
    }

    private static RagQueryRouter.Decision decisionForForcedRag(List<Document> documents) {
        if (documents.isEmpty()) {
            return RagQueryRouter.Decision.skip("no matching document chunks");
        }
        return RagQueryRouter.Decision.use("matched RAG/doc intent");
    }

    @Override
    public String getName() {
        return RagFlowStartAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

}
