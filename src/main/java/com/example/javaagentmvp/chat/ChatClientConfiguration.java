package com.example.javaagentmvp.chat;

import com.example.javaagentmvp.ChatMemoryProperties;
import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
public class ChatClientConfiguration {

    @Bean
    QwenApiLoggingAdvisor qwenApiLoggingAdvisor() {
        return new QwenApiLoggingAdvisor();
    }

    @Bean
    PostgresChatMemory postgresChatMemory(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ChatMemoryProperties chatMemoryProperties) {
        return new PostgresChatMemory(jdbcTemplate, objectMapper, chatMemoryProperties.maxMessages());
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
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor) {
        List<ToolCallback> toolCallbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(mcpClients);

        return chatClientBuilder
                .defaultSystem("""
                        你是 PostgreSQL 只读查询助手（schema: public）。用 MCP 工具执行 SQL 并中文回答。

                        规则：
                        1. 不要假设存在 id 列；写入 SQL 前先查列名：
                           SELECT column_name, data_type
                           FROM information_schema.columns
                           WHERE table_schema = 'public' AND table_name = '<表名>'
                           ORDER BY ordinal_position;
                        2. “最近 N 条”用 createtime 或 lastmodifiedtime DESC 排序（先确认列存在）。
                        3. 只执行 SELECT；不要 INSERT/UPDATE/DELETE/DDL。
                        4. 工具报错时根据报错修正 SQL 后重试，最多 2 次。
                        5. 最终回答中保留关键事实（如 changeid、buildid、使用的 SQL 摘要），便于用户追问。
                        6. 已知表结构：
                           - dbsbuild: buildid, changeid, description, createtime, lastmodifiedtime, buildtype
                           - opschange: changeid, type, scheduledtime, status, externalcode, createor, opowner, createtime, lastmodifiedtime
                        """)
                .defaultToolCallbacks(toolCallbacks)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        qwenApiLoggingAdvisor)
                .build();
    }
}
