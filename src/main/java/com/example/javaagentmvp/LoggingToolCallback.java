package com.example.javaagentmvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Wraps MCP {@link ToolCallback} to log model {@code tool_calls} and {@code TOOL} results at INFO
 * (visible in the same flow as {@link QwenApiLoggingAdvisor}).
 */
public final class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(QwenApiLoggingAdvisor.class);

    private final ToolCallback delegate;

    private LoggingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    public static ToolCallback wrap(ToolCallback delegate) {
        if (delegate instanceof LoggingToolCallback) {
            return delegate;
        }
        return new LoggingToolCallback(delegate);
    }

    public static List<ToolCallback> wrapAll(List<ToolCallback> callbacks) {
        return callbacks.stream().map(LoggingToolCallback::wrap).toList();
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
        int sessionRound = QwenApiLoggingAdvisor.activeSessionRound();
        logModelToolCalls(toolContext, sessionRound);

        String toolName = delegate.getToolDefinition().name();
        log.info("  [session round {}] executing tool_calls:\n      - name={}, args={}",
                sessionRound, toolName, QwenApiLogFormatting.truncate(toolInput));

        long startNanos = System.nanoTime();
        String responseData = delegate.call(toolInput, toolContext);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        log.info("  [session round {}] TOOL ({} ms): name={}, data={}",
                sessionRound, elapsedMs, toolName, QwenApiLogFormatting.truncate(responseData));
        return responseData;
    }

    private static void logModelToolCalls(ToolContext toolContext, int sessionRound) {
        if (toolContext == null) {
            return;
        }
        List<Message> history = toolContext.getToolCallHistory();
        if (history == null || history.isEmpty()) {
            return;
        }
        Set<String> logged = QwenApiLoggingAdvisor.loggedToolCallIds();

        for (Message message : history) {
            if (!(message instanceof AssistantMessage assistant) || !assistant.hasToolCalls()) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : assistant.getToolCalls()) {
                if (logged.add(toolCall.id())) {
                    log.info("  [session round {}] model returned tool_calls:\n      - name={}, id={}, args={}",
                            sessionRound,
                            toolCall.name(),
                            toolCall.id(),
                            QwenApiLogFormatting.truncate(toolCall.arguments()));
                }
            }
        }
    }
}
