package com.example.javaagentmvp.dbagent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts java-agent-mcp only when DB provisioning runs, so the app can boot without the binary on PATH.
 */
@Component
public class LazyProvisioningMcpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(LazyProvisioningMcpClientFactory.class);
    private static final String DEFAULT_NODE = "node";
    private static final String DEFAULT_SCRIPT = "java-agent-mcp/bin/java-agent-mcp.js";

    private final DbAgentProperties properties;
    private volatile McpSyncClient client;

    public LazyProvisioningMcpClientFactory(DbAgentProperties properties) {
        this.properties = properties;
    }

    public McpSyncClient getClient() {
        McpSyncClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client != null) {
                return client;
            }
            McpLaunch launch = resolveLaunch();
            ServerParameters serverParameters = ServerParameters.builder(launch.command())
                    .args(launch.args())
                    .build();
            log.info("Starting provisioning MCP process: {} {}", launch.command(), launch.args());
            try {
                StdioClientTransport transport = new StdioClientTransport(serverParameters);
                McpSyncClient built = McpClient.sync(transport)
                        .requestTimeout(Duration.ofSeconds(properties.provisioningTimeoutSeconds()))
                        .build();
                built.initialize();
                client = built;
                return built;
            }
            catch (RuntimeException ex) {
                throw provisioningStartFailure(launch, ex);
            }
        }
    }

    McpLaunch resolveLaunch() {
        String configured = properties.provisioningMcpCommand();
        List<String> configuredArgs = copyArgs(properties.provisioningMcpArgs());

        if (configured != null && isExistingExecutableFile(configured.strip())) {
            return new McpLaunch(configured.strip(), configuredArgs);
        }

        if (configured != null && !configured.isBlank() && looksLikeFilePath(configured)) {
            log.warn(
                    "Provisioning MCP command '{}' is not executable or missing; using node + bundled script",
                    configured);
        }

        Path script = resolveScriptPath(configuredArgs);
        validateScript(script);
        String node = (configured != null && !looksLikeFilePath(configured)) ? configured.strip() : DEFAULT_NODE;
        List<String> args = new ArrayList<>();
        args.add(script.toAbsolutePath().toString());
        if (configuredArgs.size() > 1) {
            args.addAll(configuredArgs.subList(1, configuredArgs.size()));
        }
        else if (configuredArgs.size() == 1 && !looksLikeScriptArg(configuredArgs.get(0))) {
            args.addAll(configuredArgs);
        }
        return new McpLaunch(node, List.copyOf(args));
    }

    private static List<String> copyArgs(List<String> args) {
        return args == null ? List.of() : new ArrayList<>(args);
    }

    private Path resolveScriptPath(List<String> configuredArgs) {
        if (!configuredArgs.isEmpty()) {
            Path fromArg = resolveExistingPath(configuredArgs.get(0));
            if (fromArg != null) {
                return fromArg;
            }
        }
        Path bundled = resolveExistingPath(DEFAULT_SCRIPT);
        if (bundled != null) {
            return bundled;
        }
        throw new IllegalStateException(
                "java-agent-mcp script not found. Run: cd java-agent-mcp && npm install. "
                        + "Expected at ./" + DEFAULT_SCRIPT + " (from app working directory) or set "
                        + "JAVA_AGENT_MCP_SCRIPT to the full path. "
                        + "If JAVA_AGENT_MCP_COMMAND points to a missing binary, unset it or install the MCP server.");
    }

    private static Path resolveExistingPath(String pathString) {
        Path path = Path.of(pathString);
        if (path.isAbsolute()) {
            return Files.exists(path) ? path.normalize() : null;
        }
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path resolved = cwd.resolve(path).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        return null;
    }

    private static boolean looksLikeScriptArg(String arg) {
        return arg.endsWith(".js") || arg.contains("java-agent-mcp");
    }

    static void validateExecutable(String command) {
        if (!looksLikeFilePath(command)) {
            return;
        }
        Path path = Path.of(command);
        if (!Files.exists(path)) {
            throw new IllegalStateException("Executable not found at '" + command + "'");
        }
        if (!Files.isExecutable(path)) {
            throw new IllegalStateException("Path exists but is not executable: '" + command + "'");
        }
    }

    private static void validateScript(Path script) {
        if (!Files.exists(script)) {
            throw new IllegalStateException("java-agent-mcp script not found: " + script);
        }
    }

    private static boolean isExistingExecutableFile(String command) {
        if (!looksLikeFilePath(command)) {
            return false;
        }
        Path path = Path.of(command);
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static boolean looksLikeFilePath(String command) {
        return command.contains("/") || command.startsWith(".");
    }

    private static IllegalStateException provisioningStartFailure(McpLaunch launch, RuntimeException cause) {
        String detail = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        Throwable root = cause;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        if (root instanceof IOException io && io.getMessage() != null && io.getMessage().contains("No such file")) {
            detail = "No such file or directory (is Node.js installed?)";
        }
        return new IllegalStateException(
                "Failed to start provisioning MCP (" + launch.command() + " " + launch.args() + "): " + detail,
                cause);
    }

    @PreDestroy
    public void shutdown() {
        McpSyncClient existing = client;
        if (existing != null) {
            try {
                existing.close();
            }
            catch (RuntimeException ex) {
                log.debug("Error closing provisioning MCP client", ex);
            }
            client = null;
        }
    }

    record McpLaunch(String command, List<String> args) {
    }
}
