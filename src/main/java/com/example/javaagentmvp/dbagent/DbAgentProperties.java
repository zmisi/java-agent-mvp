package com.example.javaagentmvp.dbagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "app.db-agent")
public record DbAgentProperties(
        String designDocsPath,
        String releasesPath,
        String draftSqlPromptLocation,
        Map<String, DbTarget> targets,
        String deployTarget,
        String chatTarget) {

    public DbAgentProperties {
        if (targets == null) {
            targets = Map.of();
        }
        if (deployTarget == null || deployTarget.isBlank()) {
            deployTarget = "test-deploy";
        }
        if (chatTarget == null || chatTarget.isBlank()) {
            chatTarget = "chat-readonly";
        }
    }
}
