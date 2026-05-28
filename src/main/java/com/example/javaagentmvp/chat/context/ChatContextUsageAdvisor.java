package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.core.Ordered;

/**
 * Runs immediately before {@link org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor}
 * (high {@link #getOrder()} value) so the prompt includes memory and RAG rewrites.
 */
public final class ChatContextUsageAdvisor implements CallAdvisor {

    /**
     * Between {@code QwenApiLoggingAdvisor} ({@code LOWEST_PRECEDENCE - 100}) and
     * {@code ChatModelCallAdvisor} ({@code LOWEST_PRECEDENCE}).
     */
    private static final int ORDER_BEFORE_MODEL = Ordered.LOWEST_PRECEDENCE - 50;

    private final ChatContextWindowProperties windowProperties;

    private final ChatContextUsageRegistry registry;

    public ChatContextUsageAdvisor(ChatContextWindowProperties windowProperties, ChatContextUsageRegistry registry) {
        this.windowProperties = windowProperties;
        this.registry = registry;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ContextUsageResponse usage =
                ChatContextUsageComputer.compute(request.prompt(), windowProperties.maxInputTokens());
        registry.publish(usage);
        return chain.nextCall(request);
    }

    @Override
    public String getName() {
        return ChatContextUsageAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER_BEFORE_MODEL;
    }
}
