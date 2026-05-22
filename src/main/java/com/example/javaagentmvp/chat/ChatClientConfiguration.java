package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.ChatMemoryProperties;
import com.example.javaagentmvp.LoggingToolCallback;
import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.example.javaagentmvp.chat.persistence.mapper.ChatMemoryMessageMapper;

import java.util.List;

@Configuration
@EnableConfigurationProperties(AgentPromptProperties.class)
public class ChatClientConfiguration {

    @Bean
    QwenApiLoggingAdvisor qwenApiLoggingAdvisor() {
        return new QwenApiLoggingAdvisor();
    }

    @Bean
    PostgresChatMemory postgresChatMemory(
            ChatMemoryMessageMapper chatMemoryMessageMapper,
            ObjectMapper objectMapper,
            ChatMemoryProperties chatMemoryProperties) {
        return new PostgresChatMemory(
                chatMemoryMessageMapper,
                objectMapper,
                chatMemoryProperties.maxMessages());
    }

    @Bean
    ChatMemory chatMemory(PostgresChatMemory postgresChatMemory) {
        return postgresChatMemory;
    }

    @Bean
    ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            List<McpSyncClient> mcpClients,
            ChatMemory chatMemory,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            AgentSystemPrompt agentSystemPrompt) {
        List<ToolCallback> toolCallbacks = LoggingToolCallback.wrapAll(
                SyncMcpToolCallbackProvider.syncToolCallbacks(mcpClients));

        return chatClientBuilder
                .defaultSystem(agentSystemPrompt.text())
                .defaultToolCallbacks(toolCallbacks)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        qwenApiLoggingAdvisor)
                .build();
    }
}
