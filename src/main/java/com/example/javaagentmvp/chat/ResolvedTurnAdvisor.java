package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurnContext;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurnPromptFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

/**
 * Resolves multi-turn admission intent/slots and injects a structured task block into the system prompt.
 */
public class ResolvedTurnAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ResolvedTurnAdvisor.class);

    static final int ORDER = Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50;

    private final ConversationTurnResolver turnResolver;

    public ResolvedTurnAdvisor(ConversationTurnResolver turnResolver) {
        this.turnResolver = turnResolver;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        UserTurnContextExtractor.UserTurnContext context = UserTurnContextExtractor.extract(request);
        ResolvedTurn resolved = turnResolver.resolve(
                context.currentUserMessage(),
                context.priorUserMessages(),
                context.priorContextHints());
        ResolvedTurnContext.set(resolved);

        if (!resolved.needsTaskPrompt()) {
            log.debug("ResolvedTurn: intent={}, inherited={}", resolved.intent(), resolved.inheritedIntent());
            return chain.nextCall(request);
        }

        String taskBlock = ResolvedTurnPromptFormatter.format(resolved);
        log.info("ResolvedTurn: intent={}, delta={}, inherited={}, tool={}",
                resolved.intent(), resolved.delta(), resolved.inheritedIntent(), resolved.preferredMcpTool());

        ChatClientRequest updated = request.mutate()
                .prompt(request.prompt().augmentSystemMessage(taskBlock))
                .build();
        return chain.nextCall(updated);
    }

    @Override
    public String getName() {
        return ResolvedTurnAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
