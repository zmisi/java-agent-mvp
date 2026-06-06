package com.example.javaagentmvp.dbagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "app.db-agent")
public record DbAgentProperties(
        Map<String, DbTarget> targets,
        String chatTarget) {

    public DbAgentProperties {
        if (targets == null) {
            targets = Map.of();
        }
        if (chatTarget == null || chatTarget.isBlank()) {
            chatTarget = "chat-readonly";
        }
    }
}
