package com.example.javaagentmvp.dbagent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;

@Service
public class TestDbDeployService {

    private final DbAgentTargetRegistry targetRegistry;

    public TestDbDeployService(DbAgentTargetRegistry targetRegistry) {
        this.targetRegistry = targetRegistry;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deploy(String sqlContent) {
        JdbcTemplate jdbcTemplate = targetRegistry.deployJdbcTemplate();
        String schema = targetRegistry.deploySchema();
        jdbcTemplate.execute("CREATE SCHEMA IF NOT EXISTS " + quoteIdent(schema));

        jdbcTemplate.execute((Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path TO " + quoteIdent(schema));
            }
            PostgreSqlScriptExecutor.execute(connection, sqlContent);
            return null;
        });
    }

    private static String quoteIdent(String ident) {
        if (!ident.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + ident);
        }
        return ident;
    }
}
