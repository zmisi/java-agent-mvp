package com.example.javaagentmvp.web;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/conversations")
public class ChatController {

    private final ChatClient chatClient;

    private final QwenApiLoggingAdvisor qwenApiLoggingAdvisor;

    private final AgentConversationRepository conversationRepository;

    public ChatController(
            ChatClient chatClient,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            AgentConversationRepository conversationRepository) {
        this.chatClient = chatClient;
        this.qwenApiLoggingAdvisor = qwenApiLoggingAdvisor;
        this.conversationRepository = conversationRepository;
    }

    @PostMapping("/{conversationId}/chat")
    public ChatReplyDto chat(@PathVariable String conversationId, @RequestBody ChatRequestDto body) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (!conversationRepository.exists(conversationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found");
        }

        try {
            qwenApiLoggingAdvisor.resetSessionRound();
            String reply = chatClient.prompt()
                    .user(body.message().strip())
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            Instant now = Instant.now();
            conversationRepository.touchUpdatedAt(conversationId, now);
            conversationRepository.updateTitleIfDefault(conversationId, trimTitle(body.message()), now);

            return new ChatReplyDto(reply);
        }
        catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
    }

    private static String trimTitle(String input) {
        String oneLine = input.replace('\n', ' ').replace('\r', ' ').strip();
        if (oneLine.length() > 80) {
            return oneLine.substring(0, 80) + "…";
        }
        return oneLine;
    }

    public record ChatRequestDto(String message) {
    }

    public record ChatReplyDto(String assistant) {
    }
}
