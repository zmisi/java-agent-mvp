package com.example.javaagentmvp.dbagent;

import java.util.List;

public record DbTarget(
        TargetType type,
        String server,
        List<String> servers,
        String url,
        String username,
        String password,
        String schema) {

    public enum TargetType {
        MCP,
        JDBC
    }

    /** MCP target: {@code servers} if set, otherwise single {@code server}. */
    public List<String> mcpServerNames() {
        if (servers != null && !servers.isEmpty()) {
            return List.copyOf(servers);
        }
        if (server != null && !server.isBlank()) {
            return List.of(server);
        }
        return List.of();
    }
}
