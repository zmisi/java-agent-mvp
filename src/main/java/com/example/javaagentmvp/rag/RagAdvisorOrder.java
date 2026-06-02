package com.example.javaagentmvp.rag;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.core.Ordered;

/**
 * Advisor order for RAG pipeline. Must run after {@link Advisor#DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}
 * so routing sees prior user/assistant turns from {@code MessageChatMemoryAdvisor}.
 */
final class RagAdvisorOrder {

    static final int FLOW_START = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 100;

    static final int ADMISSIONS_FORMAT = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 200;

    static final int CONDITIONAL_QA = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 300;

    static final int FLOW_LOGGING = Ordered.LOWEST_PRECEDENCE - 100;

    private RagAdvisorOrder() {
    }
}
