package com.example.javaagentmvp.web;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.auth.AuthRequestSupport;
import com.example.javaagentmvp.auth.AuthenticatedUser;
import com.example.javaagentmvp.auth.UserRole;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import com.example.javaagentmvp.chat.ConversationAccessService;
import com.example.javaagentmvp.chat.PostgresChatMemory;
import com.example.javaagentmvp.chat.ui.TranscriptBuilder;
import com.example.javaagentmvp.chat.context.ChatContextUsageRegistry;
import jakarta.servlet.http.HttpServletRequest;
import com.example.javaagentmvp.chat.context.ContextUsageResponse;
import com.example.javaagentmvp.chat.context.ConversationCompactionService;
import com.example.javaagentmvp.chat.context.ConversationTurnSummaryBuffer;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.McpTableContext;
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
    private final ConversationCompactionService conversationCompactionService;
    private final ConversationTurnSummaryBuffer turnSummaryBuffer;
    private final ConversationAccessService conversationAccess;

    private final PostgresChatMemory postgresChatMemory;

    private final String activeProvider;

    public ChatController(
            ChatClient chatClient,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            AgentConversationRepository conversationRepository,
            ChatContextUsageRegistry chatContextUsageRegistry,
            ConversationCompactionService conversationCompactionService,
            ConversationTurnSummaryBuffer turnSummaryBuffer,
            ConversationAccessService conversationAccess,
            PostgresChatMemory postgresChatMemory,
            @Value("${app.llm.provider:local}") String activeProvider) {
        this.chatClient = chatClient;
        this.qwenApiLoggingAdvisor = qwenApiLoggingAdvisor;
        this.conversationRepository = conversationRepository;
        this.chatContextUsageRegistry = chatContextUsageRegistry;
        this.conversationCompactionService = conversationCompactionService;
        this.turnSummaryBuffer = turnSummaryBuffer;
        this.conversationAccess = conversationAccess;
        this.postgresChatMemory = postgresChatMemory;
        this.activeProvider = activeProvider.strip().toLowerCase();
    }

    @PostMapping("/{conversationId}/chat")
    public ChatReplyDto chat(
            @PathVariable String conversationId,
            @RequestBody ChatRequestDto body,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider,
            HttpServletRequest request) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }
        validateRequestedProvider(requestedProvider);
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        conversationAccess.requireAccess(conversationId, user);

        String message = body.message().strip();

        try {
            qwenApiLoggingAdvisor.resetSessionRound();
            String reply = chatClient.prompt()
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            ContextUsageResponse contextUsage = chatContextUsageRegistry.consume();
            turnSummaryBuffer.appendTurn(conversationId, message, reply);
            touchConversation(conversationId, message, user);
            List<ChatTable> tables = resolveReplyTables(conversationId);
            if (!tables.isEmpty()) {
                postgresChatMemory.attachUiTablesToLatestAssistant(conversationId, tables);
            }
            return new ChatReplyDto(reply, RagFlowContext.sources(), tables, contextUsage);
        }
        catch (RuntimeException ex) {
            chatContextUsageRegistry.consume();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
        }
        finally {
            RagFlowContext.clear();
            McpTableContext.clear();
        }
    }

    @PostMapping("/{conversationId}/compact")
    public CompactReplyDto compact(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider,
            HttpServletRequest request) {
        return compactExecute(conversationId, requestedProvider, request);
    }

    @PostMapping("/{conversationId}/compact-preview")
    public CompactReplyDto compactPreview(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider,
            HttpServletRequest request) {
        return compactReview(conversationId, requestedProvider, request);
    }

    @PostMapping("/{conversationId}/compact-review")
    public CompactReplyDto compactReview(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider,
            HttpServletRequest request) {
        return compact(conversationId, requestedProvider, false, request);
    }

    @PostMapping("/{conversationId}/compact-execute")
    public CompactReplyDto compactExecute(
            @PathVariable String conversationId,
            @RequestHeader(value = "X-LLM-Provider", required = false) String requestedProvider,
            HttpServletRequest request) {
        return compact(conversationId, requestedProvider, true, request);
    }

    private CompactReplyDto compact(
            String conversationId,
            String requestedProvider,
            boolean persist,
            HttpServletRequest request) {
        validateRequestedProvider(requestedProvider);
        AuthenticatedUser user = AuthRequestSupport.requireUser(request);
        conversationAccess.requireAccess(conversationId, user);
        ConversationCompactionService.CompactionResult result = persist
                ? conversationCompactionService.compact(conversationId)
                : conversationCompactionService.preview(conversationId);
        if (persist) {
            conversationRepository.touchUpdatedAt(conversationId, Instant.now(), user.userId());
        }
        return new CompactReplyDto(
                result.summary(),
                result.beforeMessageCount(),
                result.afterMessageCount(),
                result.beforeEstimatedTokens(),
                result.afterEstimatedTokens());
    }

    private List<ChatTable> resolveReplyTables(String conversationId) {
        List<ChatTable> tables = McpTableContext.tables();
        if (!tables.isEmpty()) {
            return tables;
        }
        List<TranscriptBuilder.TranscriptRow> transcript = postgresChatMemory.listTranscript(conversationId);
        for (int i = transcript.size() - 1; i >= 0; i--) {
            TranscriptBuilder.TranscriptRow row = transcript.get(i);
            if ("assistant".equals(row.role()) && row.tables() != null && !row.tables().isEmpty()) {
                return row.tables();
            }
        }
        return List.of();
    }

    private void touchConversation(String conversationId, String message, AuthenticatedUser user) {
        Instant now = Instant.now();
        conversationRepository.touchUpdatedAt(conversationId, now, user.userId());
        conversationRepository.updateTitleIfDefault(conversationId, trimTitle(message), now, user.userId());
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
    public record ChatReplyDto(
            String assistant,
            List<RagSource> sources,
            List<ChatTable> tables,
            ContextUsageResponse contextUsage) {
    }

    public record CompactReplyDto(
            String summary,
            int beforeMessageCount,
            int afterMessageCount,
            int beforeEstimatedTokens,
            int afterEstimatedTokens
    ) {
    }
}
