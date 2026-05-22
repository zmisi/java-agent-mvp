package com.example.javaagentmvp;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.stream.Collectors;

/** Shared formatting for {@link QwenApiLoggingAdvisor} and {@link LoggingToolCallback}. */
final class QwenApiLogFormatting {

    static final int MAX_TEXT_LENGTH = 2_000;

    private QwenApiLogFormatting() {
    }

    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.length() <= MAX_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_TEXT_LENGTH) + "... (" + normalized.length() + " chars total)";
    }

    static String formatMessage(Message message) {
        return switch (message.getMessageType()) {
            case SYSTEM -> "SYSTEM: " + truncate(extractText(message));
            case USER -> "USER: " + truncate(extractText(message));
            case ASSISTANT -> formatAssistant((AssistantMessage) message);
            case TOOL -> formatToolResponse((ToolResponseMessage) message);
            default -> message.getMessageType() + ": " + truncate(message.toString());
        };
    }

    static String formatAssistant(AssistantMessage message) {
        StringBuilder sb = new StringBuilder("ASSISTANT");
        String text = message.getText();
        if (text != null && !text.isBlank()) {
            sb.append(": ").append(truncate(text));
        }
        if (message.hasToolCalls()) {
            sb.append("\n    tool_calls:");
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                sb.append("\n      - name=").append(toolCall.name())
                        .append(", id=").append(toolCall.id())
                        .append(", args=").append(truncate(toolCall.arguments()));
            }
        }
        return sb.toString();
    }

    static String formatToolResponse(ToolResponseMessage message) {
        String responses = message.getResponses().stream()
                .map(r -> "id=" + r.id() + ", name=" + r.name() + ", data=" + truncate(r.responseData()))
                .collect(Collectors.joining("; "));
        return "TOOL: " + responses;
    }

    static void appendToolDefinitions(StringBuilder sb, ChatOptions options) {
        if (!(options instanceof ToolCallingChatOptions toolOptions)) {
            return;
        }
        List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        sb.append('\n');
        sb.append("  tools: ").append(callbacks.size()).append(" registered for this request\n");
        for (ToolCallback callback : callbacks) {
            sb.append("    - ").append(callback.getToolDefinition().name()).append('\n');
        }
    }

    private static String extractText(Message message) {
        if (message instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        try {
            return message.getText();
        }
        catch (UnsupportedOperationException ex) {
            return message.toString();
        }
    }
}
