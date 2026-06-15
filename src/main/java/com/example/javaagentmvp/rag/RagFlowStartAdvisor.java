package com.example.javaagentmvp.rag;

import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurnContext;
import com.example.javaagentmvp.chat.UserTurnContextExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Starts the RAG flow: logs {@link RagFlowLogStep#QUESTION} and {@link RagFlowLogStep#RETRIEVE}.
 * when {@link RagQueryRouter} decides the message should use the knowledge base.
 */
public class RagFlowStartAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RagFlowStartAdvisor.class);

    private final RagRetrievalService ragRetrievalService;

    private final RagQueryRouter ragQueryRouter;

    private final ConversationTurnResolver turnResolver;

    private final int order;

    public RagFlowStartAdvisor(
            RagRetrievalService ragRetrievalService,
            RagQueryRouter ragQueryRouter,
            ConversationTurnResolver turnResolver) {
        this(ragRetrievalService, ragQueryRouter, turnResolver, RagAdvisorOrder.FLOW_START);
    }

    public RagFlowStartAdvisor(
            RagRetrievalService ragRetrievalService,
            RagQueryRouter ragQueryRouter,
            ConversationTurnResolver turnResolver,
            int order) {
        this.ragRetrievalService = ragRetrievalService;
        this.ragQueryRouter = ragQueryRouter;
        this.turnResolver = turnResolver;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        RagFlowContext.start();
        String flowId = RagFlowContext.flowId();
        UserTurnContextExtractor.UserTurnContext userTurnContext = UserTurnContextExtractor.extract(request);
        String userMessage = userTurnContext.currentUserMessage();

        ResolvedTurn resolved = ResolvedTurnContext.current()
                .orElseGet(() -> turnResolver.resolve(
                        userMessage,
                        userTurnContext.priorUserMessages(),
                        userTurnContext.priorContextHints()));

        RagQueryRouter.Decision preliminary = ragQueryRouter.decide(resolved, userMessage);
        if (!preliminary.useRag() && !preliminary.shouldRetrieve()) {
            RagFlowContext.skip(preliminary.reason());
            log.info("========== RAG skipped [{}] — {} ==========", flowId, preliminary.reason());
            log.info("[RAG {}] user message (MCP path):\n{}", flowId, userMessage);
            log.debug("[RAG {}] routing context — priorUserTurns={}, contextHints={}",
                    flowId, userTurnContext.priorUserMessages().size(), userTurnContext.priorContextHints().size());
            return chain.nextCall(request);
        }

        log.info("========== RAG flow start [{}] ==========", flowId);
        log.info("[RAG {}] {} 用户问题:\n{}", flowId, RagFlowLogStep.QUESTION, userMessage);

        List<Document> documents = ragRetrievalService.search(
                userMessage,
                userTurnContext.priorUserMessages(),
                userTurnContext.priorContextHints());
        RagQueryRouter.Decision finalDecision = preliminary.useRag()
                ? decisionForForcedRag(documents)
                : ragQueryRouter.afterRetrieval(preliminary, documents);

        if (!finalDecision.useRag()) {
            RagFlowContext.skip(finalDecision.reason());
            log.info("[RAG {}] skipped after retrieval — {}", flowId, finalDecision.reason());
            return chain.nextCall(request);
        }

        ragRetrievalService.logRetrievedDocuments(flowId, documents);
        RagFlowContext.setSources(documents.stream().map(RagSource::from).toList());
        return chain.nextCall(request);
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
