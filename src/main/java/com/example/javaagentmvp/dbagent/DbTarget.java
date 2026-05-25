package com.example.javaagentmvp.dbagent;

public record DbTarget(
        TargetType type,
        String server,
        String url,
        String username,
        String password,
        String schema) {

    public enum TargetType {
        MCP,
        JDBC
    }
}
