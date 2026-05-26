package com.example.javaagentmvp.dbagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app.db-agent")
public record DbAgentProperties(
        String designDocsPath,
        String releasesPath,
        String draftSqlPromptLocation,
        Map<String, DbTarget> targets,
        String deployTarget,
        String chatTarget,
        String provisioningMcpCommand,
        List<String> provisioningMcpArgs,
        int provisioningTimeoutSeconds) {

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
        if (provisioningMcpArgs == null) {
            provisioningMcpArgs = List.of();
        }
        if (provisioningTimeoutSeconds <= 0) {
            provisioningTimeoutSeconds = 7200;
        }
    }
}
