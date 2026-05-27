package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.document.Document;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Starts the RAG flow: logs {@link RagFlowLogStep#QUESTION} and {@link RagFlowLogStep#RETRIEVE}.
 * when {@link RagQueryRouter} decides the message should use the knowledge base.
 */
public class RagFlowStartAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RagFlowStartAdvisor.class);

    private final RagRetrievalService ragRetrievalService;

    private final RagQueryRouter ragQueryRouter;

    private final int order;

    public RagFlowStartAdvisor(RagRetrievalService ragRetrievalService, RagQueryRouter ragQueryRouter) {
        this(ragRetrievalService, ragQueryRouter, Ordered.HIGHEST_PRECEDENCE + 100);
    }

    public RagFlowStartAdvisor(RagRetrievalService ragRetrievalService, RagQueryRouter ragQueryRouter, int order) {
        this.ragRetrievalService = ragRetrievalService;
        this.ragQueryRouter = ragQueryRouter;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        RagFlowContext.start();
        String flowId = RagFlowContext.flowId();
        String userMessage = extractUserMessage(request);

        RagQueryRouter.Decision preliminary = ragQueryRouter.decide(userMessage);
        if (!preliminary.useRag() && !preliminary.shouldRetrieve()) {
            RagFlowContext.skip(preliminary.reason());
            log.info("========== RAG skipped [{}] — {} ==========", flowId, preliminary.reason());
            log.info("[RAG {}] user message (MCP path):\n{}", flowId, userMessage);
            return chain.nextCall(request);
        }

        log.info("========== RAG flow start [{}] ==========", flowId);
        log.info("[RAG {}] {} 用户问题:\n{}", flowId, RagFlowLogStep.QUESTION, userMessage);

        List<Document> documents = ragRetrievalService.search(userMessage);
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

    private static String extractUserMessage(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.USER) {
                String text = message.getText();
                return text == null ? "" : text;
            }
        }
        return "";
    }
}
