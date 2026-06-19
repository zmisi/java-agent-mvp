package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryContext;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
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
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatTurnService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final QwenApiLoggingAdvisor qwenApiLoggingAdvisor;
    private final ChatContextUsageRegistry chatContextUsageRegistry;
    private final PostgresChatMemory postgresChatMemory;

    public ChatTurnService(
            ChatClient chatClient,
            ChatMemory chatMemory,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            ChatContextUsageRegistry chatContextUsageRegistry,
            PostgresChatMemory postgresChatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.qwenApiLoggingAdvisor = qwenApiLoggingAdvisor;
        this.chatContextUsageRegistry = chatContextUsageRegistry;
        this.postgresChatMemory = postgresChatMemory;
    }

    public ChatController.ChatReplyDto execute(String conversationId, String message) {
        ChatTurnFlowLog.begin(conversationId, message);
        try {
            qwenApiLoggingAdvisor.resetSessionRound();
            String reply = chatClient.prompt()
                    .user(message)
                    .advisors(conversationMemoryAdvisor(conversationId))
                    .call()
                    .content();

            reply = applyDeterministicRankReply(conversationId, reply);

            ContextUsageResponse contextUsage = chatContextUsageRegistry.consume();
            List<ChatTable> tables = withRankProvinces(McpTableContext.tables());
            if (!tables.isEmpty()) {
                postgresChatMemory.attachUiTablesToLatestAssistant(conversationId, tables);
            }
            AdmissionQueryIr admissionQuery = AdmissionQueryContext.current().orElse(null);
            ChatTurnFlowLog.end(
                    "task=%s needsClarification=%s tables=%d sources=%d replyChars=%d",
                    admissionQuery == null ? "?" : admissionQuery.task(),
                    admissionQuery == null ? List.of() : admissionQuery.needsClarification(),
                    tables.size(),
                    RagFlowContext.sources().size(),
                    reply == null ? 0 : reply.length());
            return new ChatController.ChatReplyDto(
                    reply,
                    RagFlowContext.sources(),
                    tables,
                    contextUsage,
                    admissionQuery == null ? null : admissionQuery.task(),
                    admissionQuery == null ? null : admissionQuery.needsClarification());
        }
        catch (RuntimeException ex) {
            chatContextUsageRegistry.consume();
            if (ChatTurnFlowLog.active()) {
                ChatTurnFlowLog.end("failed error=%s", ex.getMessage());
            }
            throw ex;
        }
        finally {
            RagFlowContext.clear();
            McpTableContext.clear();
            McpRankContext.clear();
            ResolvedTurnContext.clear();
            AdmissionQueryContext.clear();
            ChatTurnFlowLog.clear();
        }
    }

    private MessageChatMemoryAdvisor conversationMemoryAdvisor(String conversationId) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(conversationId)
                .build();
    }

    private String applyDeterministicRankReply(String conversationId, String llmReply) {
        List<McpRankContext.RankCapture> captures = McpRankContext.captures();
        AdmissionQueryIr query = AdmissionQueryContext.current().orElse(null);
        if (captures.isEmpty()) {
            String noData = rankNoDataMessage(query);
            if (noData != null) {
                postgresChatMemory.replaceLatestAssistantText(conversationId, noData);
                return noData;
            }
            return llmReply;
        }
        Integer score = captures.get(0).score();
        List<String> provinces = captures.stream()
                .map(McpRankContext.RankCapture::province)
                .filter(province -> province != null && !province.isBlank())
                .distinct()
                .toList();
        String intro = captures.size() == 1
                ? RankResponseFormatter.formatIntro(captures.get(0).rankResult(), score, captures.get(0).province())
                : RankResponseFormatter.formatIntroForProvinces(score, provinces);
        if (intro != null && !intro.isBlank()) {
            postgresChatMemory.replaceLatestAssistantText(conversationId, intro);
            return intro;
        }
        return llmReply;
    }

    private static String rankNoDataMessage(AdmissionQueryIr query) {
        if (query == null || !"search_rank".equals(query.task())) {
            return null;
        }
        if (query.slots().provincesOrEmpty().isEmpty() && query.regions().isEmpty()) {
            return null;
        }
        List<String> regionPhrases = query.regions().stream()
                .map(region -> region.phrase())
                .toList();
        return RankResponseFormatter.formatNoRankDataMessage(
                query.slots().score(),
                regionPhrases,
                query.slots().provincesOrEmpty());
    }

    private static List<ChatTable> withRankProvinces(List<ChatTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<McpRankContext.RankCapture> captures = McpRankContext.captures();
        if (captures.isEmpty()) {
            return tables;
        }
        List<ChatTable> enriched = new ArrayList<>(tables.size());
        int captureIndex = 0;
        for (ChatTable table : tables) {
            if (!isRankTable(table)) {
                enriched.add(table);
                continue;
            }
            String province = table.province();
            if (province == null || province.isBlank()) {
                province = table.title();
            }
            if ((province == null || province.isBlank()) && captureIndex < captures.size()) {
                province = captures.get(captureIndex).province();
            }
            if (province != null && !province.isBlank()) {
                enriched.add(table.withProvince(province.strip()));
                captureIndex++;
            }
            else {
                enriched.add(table);
            }
        }
        return enriched;
    }

    private static boolean isRankTable(ChatTable table) {
        if (table == null || table.columns() == null) {
            return false;
        }
        return table.columns().stream()
                .anyMatch(column -> "year_label".equals(column.key()) || "rank_range".equals(column.key()));
    }
}
