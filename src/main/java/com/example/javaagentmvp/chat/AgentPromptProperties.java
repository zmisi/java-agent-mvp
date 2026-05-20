package com.example.javaagentmvp.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * System prompt file location and optional template variables.
 */
@ConfigurationProperties(prefix = "app.agent.prompt")
public record AgentPromptProperties(
        String location,
        String schema) {

    public AgentPromptProperties {
        if (location == null || location.isBlank()) {
            location = "classpath:prompts/db-agent-system.md";
        }
        if (schema == null || schema.isBlank()) {
            schema = "public";
        }
    }
}
