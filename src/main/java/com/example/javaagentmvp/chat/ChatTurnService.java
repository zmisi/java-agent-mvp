package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.admissionworkflow.format.RankResponseFormatter;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurnContext;
import com.example.javaagentmvp.chat.context.ChatContextUsageRegistry;
import com.example.javaagentmvp.chat.context.ContextUsageResponse;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.chat.ui.McpRankContext;
import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.rag.RagFlowContext;
import com.example.javaagentmvp.rag.RagSource;
import com.example.javaagentmvp.web.ChatController;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatTurnService {

    private final ChatClient chatClient;
    private final QwenApiLoggingAdvisor qwenApiLoggingAdvisor;
    private final ChatContextUsageRegistry chatContextUsageRegistry;
    private final PostgresChatMemory postgresChatMemory;

    public ChatTurnService(
            ChatClient chatClient,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            ChatContextUsageRegistry chatContextUsageRegistry,
            PostgresChatMemory postgresChatMemory) {
        this.chatClient = chatClient;
        this.qwenApiLoggingAdvisor = qwenApiLoggingAdvisor;
        this.chatContextUsageRegistry = chatContextUsageRegistry;
        this.postgresChatMemory = postgresChatMemory;
    }

    public ChatController.ChatReplyDto execute(String conversationId, String message) {
        try {
            qwenApiLoggingAdvisor.resetSessionRound();
            String reply = chatClient.prompt()
                    .user(message)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();

            reply = applyDeterministicRankReply(conversationId, reply);

            ContextUsageResponse contextUsage = chatContextUsageRegistry.consume();
            List<ChatTable> tables = McpTableContext.tables();
            if (!tables.isEmpty()) {
                postgresChatMemory.attachUiTablesToLatestAssistant(conversationId, tables);
            }
            return new ChatController.ChatReplyDto(reply, RagFlowContext.sources(), tables, contextUsage);
        }
        catch (RuntimeException ex) {
            chatContextUsageRegistry.consume();
            throw ex;
        }
        finally {
            RagFlowContext.clear();
            McpTableContext.clear();
            McpRankContext.clear();
            ResolvedTurnContext.clear();
        }
    }

    private String applyDeterministicRankReply(String conversationId, String llmReply) {
        return McpRankContext.capture()
                .map(capture -> {
                    String intro = RankResponseFormatter.formatIntro(
                            capture.rankResult(), capture.score(), capture.province());
                    if (intro != null && !intro.isBlank()) {
                        postgresChatMemory.replaceLatestAssistantText(conversationId, intro);
                        return intro;
                    }
                    return llmReply;
                })
                .orElse(llmReply);
    }
}
