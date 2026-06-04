package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.ChatMemoryProperties;
import com.example.javaagentmvp.LoggingToolCallback;
import com.example.javaagentmvp.McpTableCapturingToolCallback;
import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.dbagent.DbAgentTargetRegistry;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.example.javaagentmvp.rag.AdmissionsAnswerFormatAdvisor;
import com.example.javaagentmvp.rag.ConditionalQuestionAnswerAdvisor;
import com.example.javaagentmvp.rag.RagFlowLoggingAdvisor;
import com.example.javaagentmvp.rag.RagFlowStartAdvisor;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.javaagentmvp.chat.context.ChatContextUsageAdvisor;
import com.example.javaagentmvp.chat.context.ChatContextUsageRegistry;
import com.example.javaagentmvp.chat.context.ChatContextWindowProperties;
import com.example.javaagentmvp.chat.persistence.mapper.ChatMemoryMessageMapper;

import java.util.List;

@Configuration
@EnableConfigurationProperties({AgentPromptProperties.class, RagProperties.class})
public class ChatClientConfiguration {

    @Bean
    QwenApiLoggingAdvisor qwenApiLoggingAdvisor() {
        return new QwenApiLoggingAdvisor();
    }

    @Bean
    PostgresChatMemory postgresChatMemory(
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            ObjectMapper objectMapper,
            McpTableExtractor mcpTableExtractor,
            ChatMemoryProperties chatMemoryProperties) {
        return new PostgresChatMemory(
                chatMemoryMessageMapper,
                objectMapper,
                mcpTableExtractor,
                chatMemoryProperties.maxMessages());
    }

    @Bean
    ChatMemory chatMemory(PostgresChatMemory postgresChatMemory) {
        return postgresChatMemory;
    }

    @Bean
    ChatContextUsageAdvisor chatContextUsageAdvisor(
            ChatContextWindowProperties chatContextWindowProperties,
            ChatContextUsageRegistry chatContextUsageRegistry) {
        return new ChatContextUsageAdvisor(chatContextWindowProperties, chatContextUsageRegistry);
    }

    @Bean
    ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            DbAgentTargetRegistry dbAgentTargetRegistry,
            ChatMemory chatMemory,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            AgentSystemPrompt agentSystemPrompt,
            RagProperties ragProperties,
            ObjectProvider<RagFlowStartAdvisor> ragFlowStartAdvisor,
            ObjectProvider<AdmissionsAnswerFormatAdvisor> admissionsAnswerFormatAdvisor,
            ObjectProvider<ConditionalQuestionAnswerAdvisor> conditionalQuestionAnswerAdvisor,
            ObjectProvider<RagFlowLoggingAdvisor> ragFlowLoggingAdvisor,
            ChatContextUsageAdvisor chatContextUsageAdvisor,
            McpTableExtractor mcpTableExtractor,
            ObjectMapper objectMapper) {
        List<ToolCallback> toolCallbacks = McpTableCapturingToolCallback.wrapAll(
                LoggingToolCallback.wrapAll(
                        SyncMcpToolCallbackProvider.syncToolCallbacks(dbAgentTargetRegistry.chatMcpClients())),
                mcpTableExtractor,
                objectMapper);

        var builder = chatClientBuilder
                .defaultSystem(buildSystemPrompt(agentSystemPrompt, ragProperties))
                .defaultToolCallbacks(toolCallbacks)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        qwenApiLoggingAdvisor,
                        chatContextUsageAdvisor);

        ragFlowStartAdvisor.ifAvailable(builder::defaultAdvisors);
        admissionsAnswerFormatAdvisor.ifAvailable(builder::defaultAdvisors);
        conditionalQuestionAnswerAdvisor.ifAvailable(builder::defaultAdvisors);
        ragFlowLoggingAdvisor.ifAvailable(builder::defaultAdvisors);

        return builder.build();
    }

    private static String buildSystemPrompt(AgentSystemPrompt agentSystemPrompt, RagProperties ragProperties) {
        if (!ragProperties.enabled() || ragProperties.contextAddon() == null || ragProperties.contextAddon().isBlank()) {
            return agentSystemPrompt.text();
        }
        return agentSystemPrompt.text() + "\n\n" + ragProperties.contextAddon().strip();
    }
}
