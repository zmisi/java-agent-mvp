package com.example.javaagentmvp.dbagent;

import com.zaxxer.hikari.HikariDataSource;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DbAgentTargetRegistry {

    private static final Logger log = LoggerFactory.getLogger(DbAgentTargetRegistry.class);

    private final DbAgentProperties properties;
    private final DataSourceProperties dataSourceProperties;
    private final JdbcTemplate primaryJdbcTemplate;
    private final List<McpSyncClient> mcpClients;
    private final Map<String, JdbcTemplate> jdbcTemplates = new ConcurrentHashMap<>();

    public DbAgentTargetRegistry(
            DbAgentProperties properties,
            DataSourceProperties dataSourceProperties,
            JdbcTemplate primaryJdbcTemplate,
            List<McpSyncClient> mcpClients) {
        this.properties = properties;
        this.dataSourceProperties = dataSourceProperties;
        this.primaryJdbcTemplate = primaryJdbcTemplate;
        this.mcpClients = mcpClients;
        logTargets();
    }

    public String deployTargetKey() {
        return properties.deployTarget();
    }

    public String chatTargetKey() {
        return properties.chatTarget();
    }

    public String deploySchema() {
        return requireJdbcTarget(deployTargetKey()).schema();
    }

    public JdbcTemplate deployJdbcTemplate() {
        DbTarget target = requireJdbcTarget(deployTargetKey());
        JdbcConnectionSettings settings = resolveJdbcSettings(target);
        if (matchesPrimary(settings)) {
            return primaryJdbcTemplate;
        }
        return jdbcTemplates.computeIfAbsent(deployTargetKey(), key -> new JdbcTemplate(createDataSource(settings)));
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
        DbTarget deploy = requireTarget(deployTargetKey());
        DbTarget chat = requireTarget(chatTargetKey());
        log.info(
                "DB Agent targets: deploy='{}' type={} schema={}, chat='{}' type={} server={}",
                deployTargetKey(),
                deploy.type(),
                deploy.type() == DbTarget.TargetType.JDBC ? deploy.schema() : "-",
                chatTargetKey(),
                chat.type(),
                chat.type() == DbTarget.TargetType.MCP ? chat.mcpServerNames() : "-");
    }

    private DbTarget requireTarget(String key) {
        DbTarget target = properties.targets().get(key);
        if (target == null) {
            throw new IllegalStateException("Unknown db-agent target: " + key);
        }
        if (target.type() == null) {
            throw new IllegalStateException("Target '" + key + "' is missing type (mcp or jdbc)");
        }
        return target;
    }

    private DbTarget requireJdbcTarget(String key) {
        DbTarget target = requireTarget(key);
        if (target.type() != DbTarget.TargetType.JDBC) {
            throw new IllegalStateException("Target '" + key + "' must be jdbc, found " + target.type());
        }
        if (target.schema() == null || target.schema().isBlank()) {
            throw new IllegalStateException("JDBC target '" + key + "' requires schema");
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

    private JdbcConnectionSettings resolveJdbcSettings(DbTarget target) {
        String url = firstNonBlank(target.url(), dataSourceProperties.getUrl());
        String username = firstNonBlank(target.username(), dataSourceProperties.getUsername());
        String password = target.password() != null ? target.password() : dataSourceProperties.getPassword();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("JDBC target requires url or spring.datasource.url");
        }
        return new JdbcConnectionSettings(url, username, password);
    }

    private boolean matchesPrimary(JdbcConnectionSettings settings) {
        return settings.url().equals(dataSourceProperties.getUrl())
                && nullSafeEquals(settings.username(), dataSourceProperties.getUsername())
                && nullSafeEquals(settings.password(), dataSourceProperties.getPassword());
    }

    private static DataSource createDataSource(JdbcConnectionSettings settings) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(settings.url());
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setMaximumPoolSize(4);
        return dataSource;
    }

    private static boolean mcpClientMatchesServer(McpSyncClient client, String server) {
        String clientName = client.getClientInfo().name();
        return clientName.equals(server) || clientName.contains(server);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private static boolean nullSafeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private record JdbcConnectionSettings(String url, String username, String password) {
    }
}
