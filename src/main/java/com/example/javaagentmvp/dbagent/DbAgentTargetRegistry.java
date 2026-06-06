package com.example.javaagentmvp.dbagent;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class DbAgentTargetRegistry {

    private static final Logger log = LoggerFactory.getLogger(DbAgentTargetRegistry.class);

    private final DbAgentProperties properties;
    private final List<McpSyncClient> mcpClients;

    public DbAgentTargetRegistry(DbAgentProperties properties, List<McpSyncClient> mcpClients) {
        this.properties = properties;
        this.mcpClients = mcpClients;
        logTargets();
    }

    public String chatTargetKey() {
        return properties.chatTarget();
    }

    public List<McpSyncClient> chatMcpClients() {
        DbTarget target = requireMcpTarget(chatTargetKey());
        List<String> serverNames = target.mcpServerNames();
        List<McpSyncClient> filtered = mcpClients.stream()
                .filter(client -> serverNames.stream().anyMatch(name -> mcpClientMatchesServer(client, name)))
                .toList();
        if (filtered.isEmpty()) {
            throw new IllegalStateException(
                    "No MCP client matched servers " + serverNames + " for chat target '" + chatTargetKey() + "'");
        }
        return filtered;
    }

    private void logTargets() {
        DbTarget chat = requireTarget(chatTargetKey());
        log.info(
                "DB Agent chat target='{}' type={} servers={}",
                chatTargetKey(),
                chat.type(),
                chat.mcpServerNames());
    }

    private DbTarget requireTarget(String key) {
        DbTarget target = properties.targets().get(key);
        if (target == null) {
            throw new IllegalStateException("Unknown db-agent target: " + key);
        }
        if (target.type() == null) {
            throw new IllegalStateException("Target '" + key + "' is missing type (mcp)");
        }
        return target;
    }

    private DbTarget requireMcpTarget(String key) {
        DbTarget target = requireTarget(key);
        if (target.type() != DbTarget.TargetType.MCP) {
            throw new IllegalStateException("Target '" + key + "' must be mcp, found " + target.type());
        }
        if (target.mcpServerNames().isEmpty()) {
            throw new IllegalStateException("MCP target '" + key + "' requires server or servers");
        }
        return target;
    }

    private static boolean mcpClientMatchesServer(McpSyncClient client, String server) {
        String clientName = client.getClientInfo().name();
        return clientName.equals(server) || clientName.contains(server);
    }
}
