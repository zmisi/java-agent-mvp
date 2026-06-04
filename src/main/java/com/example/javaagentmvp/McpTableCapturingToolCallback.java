package com.example.javaagentmvp;

import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;

/** Wraps MCP {@link ToolCallback} to capture structured tables for UI clients. */
public final class McpTableCapturingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final McpTableExtractor mcpTableExtractor;

    private McpTableCapturingToolCallback(ToolCallback delegate, McpTableExtractor mcpTableExtractor) {
        this.delegate = delegate;
        this.mcpTableExtractor = mcpTableExtractor;
    }

    public static ToolCallback wrap(ToolCallback delegate, McpTableExtractor mcpTableExtractor) {
        if (delegate instanceof McpTableCapturingToolCallback) {
            return delegate;
        }
        return new McpTableCapturingToolCallback(delegate, mcpTableExtractor);
    }

    public static List<ToolCallback> wrapAll(List<ToolCallback> callbacks, McpTableExtractor mcpTableExtractor) {
        return callbacks.stream().map(callback -> wrap(callback, mcpTableExtractor)).toList();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        String responseData = delegate.call(toolInput, toolContext);
        mcpTableExtractor.extract(toolName, toolInput, responseData).ifPresent(McpTableContext::add);
        return responseData;
    }
}
