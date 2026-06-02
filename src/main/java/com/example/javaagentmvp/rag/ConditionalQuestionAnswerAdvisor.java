package com.example.javaagentmvp.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * Applies {@link QuestionAnswerAdvisor} only when {@link RagFlowContext} has not marked the
 * request as a database/MCP query (RAG skipped).
 */
public class ConditionalQuestionAnswerAdvisor implements CallAdvisor {

    private final QuestionAnswerAdvisor delegate;

    private final int order;

    public ConditionalQuestionAnswerAdvisor(QuestionAnswerAdvisor delegate) {
        this(delegate, RagAdvisorOrder.CONDITIONAL_QA);
    }

    public ConditionalQuestionAnswerAdvisor(QuestionAnswerAdvisor delegate, int order) {
        this.delegate = delegate;
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (RagFlowContext.isSkipped() || RagFlowContext.isMultiSchoolAdmissions()) {
            return chain.nextCall(request);
        }
        return delegate.adviseCall(request, chain);
    }

    @Override
    public String getName() {
        return ConditionalQuestionAnswerAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }
}
