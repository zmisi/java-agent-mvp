package com.example.javaagentmvp.dbagent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@Configuration
public class DbAgentConfiguration {

    @Bean
    DbAgentTargetRegistry dbAgentTargetRegistry(
            DbAgentProperties properties,
            DataSourceProperties dataSourceProperties,
            JdbcTemplate jdbcTemplate,
            List<McpSyncClient> mcpClients) {
        return new DbAgentTargetRegistry(properties, dataSourceProperties, jdbcTemplate, mcpClients);
    }

    @Bean
    ChatClient draftSqlChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
