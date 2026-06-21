package com.example.javaagentmvp.web;

import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.ChatTurnService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/public")
public class PublicChatController {

    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{8,64}$");

    private final ChatTurnService chatTurnService;
    private final AgentConversationRepository conversationRepository;
    private final String activeProvider;

    public PublicChatController(
            ChatTurnService chatTurnService,
            AgentConversationRepository conversationRepository,
            @Value("${app.llm.provider:local}") String activeProvider) {
        this.chatTurnService = chatTurnService;
        this.conversationRepository = conversationRepository;
        this.activeProvider = activeProvider.strip().toLowerCase();
    }

    @PostMapping("/chat")
    public ChatController.ChatReplyDto chat(
            @RequestBody PublicChatRequest body,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        if (body.sessionId() == null || !SESSION_ID_PATTERN.matcher(body.sessionId().strip()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        validateRequestedProvider(requestedProvider);

        String conversationId = "guest-" + body.sessionId().strip();
        String message = body.message().strip();
        Instant now = Instant.now();
        conversationRepository.insertIfMissing(conversationId, "访客对话", now);
        conversationRepository.touchUpdatedAt(conversationId, now, null);
        try {
            return chatTurnService.execute(conversationId, message, null, "guest");
        }
        catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
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

    public record PublicChatRequest(String sessionId, String message) {
    }
}
