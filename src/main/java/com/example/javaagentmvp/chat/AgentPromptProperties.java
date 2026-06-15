package com.example.javaagentmvp.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * System prompt file location and optional template variables.
 */
@ConfigurationProperties(prefix = "app.agent.prompt")
public record AgentPromptProperties(
        String location,
        String scoreMajorPromptLocation,
        String rankQueryPromptLocation,
        String policyQueryPromptLocation,
        String schema) {

    public AgentPromptProperties {
        if (location == null || location.isBlank()) {
            location = "classpath:prompts/db-agent-system.md";
        }
        if (scoreMajorPromptLocation == null || scoreMajorPromptLocation.isBlank()) {
            scoreMajorPromptLocation = "classpath:prompts/admission-score-major-synthesis.md";
        }
        if (rankQueryPromptLocation == null || rankQueryPromptLocation.isBlank()) {
            rankQueryPromptLocation = "classpath:prompts/admission-rank-query.md";
        }
        if (policyQueryPromptLocation == null || policyQueryPromptLocation.isBlank()) {
            policyQueryPromptLocation = "classpath:prompts/admission-policy-query.md";
        }
        if (schema == null || schema.isBlank()) {
            schema = "emp";
        }
    }
}