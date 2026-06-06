package com.example.javaagentmvp.dbagent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DbAgentConfiguration {

    @Bean
    DbAgentTargetRegistry dbAgentTargetRegistry(
            DbAgentProperties properties,
            List<McpSyncClient> mcpClients) {
        return new DbAgentTargetRegistry(properties, mcpClients);
    }
}
