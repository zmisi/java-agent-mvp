package com.example.javaagentmvp.web;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.context.ChatContextUsageRegistry;
import com.example.javaagentmvp.chat.context.ContextUsageResponse;
import com.example.javaagentmvp.rag.RagFlowContext;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private final ChatClient chatClient;

    private final QwenApiLoggingAdvisor qwenApiLoggingAdvisor;

    private final AgentConversationRepository conversationRepository;

    private final ChatContextUsageRegistry chatContextUsageRegistry;

    private final String activeProvider;

    public ChatController(
            ChatClient chatClient,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            AgentConversationRepository conversationRepository,
            ChatContextUsageRegistry chatContextUsageRegistry,
            @Value("${app.llm.provider:local}") String activeProvider) {
        this.chatClient = chatClient;
        this.qwenApiLoggingAdvisor = qwenApiLoggingAdvisor;
        this.conversationRepository = conversationRepository;
        this.chatContextUsageRegistry = chatContextUsageRegistry;
        this.activeProvider = activeProvider.strip().toLowerCase();
    }

    @PostMapping("/{conversationId}/chat")
    public ChatReplyDto chat(
            @PathVariable String conversationId,
            @RequestBody ChatRequestDto body,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        validateRequestedProvider(requestedProvider);
        if (!conversationRepository.exists(conversationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }

        String message = body.message().strip();

        try {
            qwenApiLoggingAdvisor.resetSessionRound();
            String reply = chatClient.prompt()
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            ContextUsageResponse contextUsage = chatContextUsageRegistry.consume();
            touchConversation(conversationId, message);
            return new ChatReplyDto(reply, RagFlowContext.sources(), contextUsage);
        }
        catch (RuntimeException ex) {
            chatContextUsageRegistry.consume();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
        finally {
            RagFlowContext.clear();
        }
    }

    private void touchConversation(String conversationId, String message) {
        Instant now = Instant.now();
        conversationRepository.touchUpdatedAt(conversationId, now);
        conversationRepository.updateTitleIfDefault(conversationId, trimTitle(message), now);
    }

    private static String trimTitle(String input) {
        String oneLine = input.replace('\n', ' ').replace('\r', ' ').strip();
        if (oneLine.length() > 80) {
            return oneLine.substring(0, 80) + "…";
        }
        return oneLine;
    }

    private void validateRequestedProvider(String requestedProvider) {
        if (requestedProvider == null || requestedProvider.isBlank()) {
            return;
        }
        String normalized = requestedProvider.strip().toLowerCase();
        if (!normalized.equals("local") && !normalized.equals("online")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-LLM-Provider must be local or online");
        }
        if (!normalized.equals(activeProvider)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "X-LLM-Provider=" + normalized + " does not match active profile provider=" + activeProvider);
        }
    }

    public record ChatRequestDto(String message) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChatReplyDto(String assistant, List<RagSource> sources, ContextUsageResponse contextUsage) {
    }
}
