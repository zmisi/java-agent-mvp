package com.example.javaagentmvp.dbagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyProvisioningMcpClientFactoryTest {

    @TempDir
    Path tempDir;

    @Test
    void validateExecutable_rejectsMissingFile() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> LazyProvisioningMcpClientFactory.validateExecutable("/tmp/does-not-exist-java-agent-mcp"));
        assertTrue(ex.getMessage().contains("not found"));
    }

    @Test
    void validateExecutable_acceptsExecutableFile() throws Exception {
        Path script = tempDir.resolve("java-agent-mcp");
        Files.writeString(script, "#!/bin/sh\necho ok\n");
        script.toFile().setExecutable(true);
        assertDoesNotThrow(() -> LazyProvisioningMcpClientFactory.validateExecutable(script.toString()));
    }

    @Test
    void resolveLaunch_fallsBackToBundledScriptWhenConfiguredBinaryMissing() throws Exception {
        Path projectRoot = tempDir.resolve("project");
        Path mcpDir = projectRoot.resolve("java-agent-mcp/bin");
        Files.createDirectories(mcpDir);
        Path script = mcpDir.resolve("java-agent-mcp.js");
        Files.writeString(script, "// mcp\n");

        Path previousCwd = Path.of(System.getProperty("user.dir"));
        try {
            System.setProperty("user.dir", projectRoot.toString());
            DbAgentProperties props = new DbAgentProperties(
                    null, null, null, Map.of(), null, null,
                    "/usr/local/bin/java-agent-mcp", List.of("./java-agent-mcp/bin/java-agent-mcp.js"), 600);
            LazyProvisioningMcpClientFactory factory = new LazyProvisioningMcpClientFactory(props);
            LazyProvisioningMcpClientFactory.McpLaunch launch = factory.resolveLaunch();
            assertEquals("node", launch.command());
            assertEquals(1, launch.args().size());
            assertTrue(launch.args().get(0).endsWith("java-agent-mcp.js"));
        }
        finally {
            System.setProperty("user.dir", previousCwd.toString());
        }
    }

    private static void assertDoesNotThrow(Runnable runnable) {
        runnable.run();
    }
}
