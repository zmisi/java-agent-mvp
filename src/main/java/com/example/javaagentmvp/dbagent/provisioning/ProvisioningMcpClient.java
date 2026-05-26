package com.example.javaagentmvp.dbagent.provisioning;

import com.example.javaagentmvp.dbagent.LazyProvisioningMcpClientFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ProvisioningMcpClient {

    private final LazyProvisioningMcpClientFactory mcpFactory;
    private final ObjectMapper objectMapper;

    public ProvisioningMcpClient(LazyProvisioningMcpClientFactory mcpFactory, ObjectMapper objectMapper) {
        this.mcpFactory = mcpFactory;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = mcpFactory.getClient();
        McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
        String text = extractText(result, toolName);
        Map<String, Object> parsed = parseJson(toolName, text);
        if (Boolean.TRUE.equals(result.isError())) {
            throw toolFailed(toolName, parsed, text);
        }
        return parsed;
    }

    private String extractText(McpSchema.CallToolResult result, String toolName) {
        if (result.content() == null || result.content().isEmpty()) {
            throw new IllegalStateException("MCP tool " + toolName + " returned empty content");
        }
        McpSchema.Content content = result.content().get(0);
        if (!(content instanceof McpSchema.TextContent textContent)) {
            throw new IllegalStateException("MCP tool " + toolName + " returned non-text content");
        }
        return textContent.text();
    }

    private Map<String, Object> parseJson(String toolName, String text) {
        try {
            return objectMapper.readValue(text, new TypeReference<>() {
            });
        }
        catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to parse MCP tool response for " + toolName + ": " + ex.getMessage() + ". Raw: " + text,
                    ex);
        }
    }

    private static IllegalStateException toolFailed(String toolName, Map<String, Object> parsed, String raw) {
        String error = parsed.get("error") != null ? parsed.get("error").toString() : "unknown error";
        String log = parsed.get("logExcerpt") != null ? parsed.get("logExcerpt").toString() : "";
        String message = toolName + " failed: " + error;
        if (!log.isBlank()) {
            message = message + "\n" + log;
        }
        else if (raw != null && !raw.isBlank() && !raw.equals(parsed.toString())) {
            message = message + "\n" + raw;
        }
        return new IllegalStateException(message);
    }

    public Map<String, Object> connectionArgs(DbProvisioningService.PreflightProvisioningCommand ctx) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("host", ctx.host());
        args.put("sshPort", ctx.sshPort());
        args.put("sshUser", ctx.sshUser());
        args.put("authType", ctx.authType());
        if (ctx.sshPassword() != null) {
            args.put("password", ctx.sshPassword());
        }
        if (ctx.privateKeyPem() != null) {
            args.put("privateKeyPem", ctx.privateKeyPem());
        }
        if (ctx.privateKeyPassphrase() != null) {
            args.put("passphrase", ctx.privateKeyPassphrase());
        }
        return args;
    }

    public Map<String, Object> connectionArgs(ProvisioningJobContext ctx) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("host", ctx.host());
        args.put("sshPort", ctx.sshPort());
        args.put("sshUser", ctx.sshUser());
        args.put("authType", ctx.authType());
        if (ctx.sshPassword() != null) {
            args.put("password", ctx.sshPassword());
        }
        if (ctx.privateKeyPem() != null) {
            args.put("privateKeyPem", ctx.privateKeyPem());
        }
        if (ctx.privateKeyPassphrase() != null) {
            args.put("passphrase", ctx.privateKeyPassphrase());
        }
        return args;
    }
}
