package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link ContextUsageResponse} from the final {@link Prompt} assembled for the model
 * (after memory / RAG advisors). Tool definitions are counted separately from chat messages.
 */
public final class ChatContextUsageComputer {

    private ChatContextUsageComputer() {
    }

    public static ContextUsageResponse compute(Prompt prompt, int contextWindowTokens) {
        List<Message> messages = prompt.getInstructions();
        int lastUserIndex = findLastUserIndex(messages);

        int systemTokens = 0;
        int systemChars = 0;
        int memUserTokens = 0;
        int memUserChars = 0;
        int memAssistantTokens = 0;
        int memAssistantChars = 0;
        int memToolTokens = 0;
        int memToolChars = 0;
        int currentUserTokens = 0;
        int currentUserChars = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            String payload = fullMessagePayload(m);
            int chars = payload.length();
            int toks = ApproximateTokenEstimator.estimateTokens(payload);
            MessageType t = m.getMessageType();
            if (t == MessageType.SYSTEM) {
                systemChars += chars;
                systemTokens += toks;
            }
            else if (t == MessageType.USER && i == lastUserIndex) {
                currentUserChars += chars;
                currentUserTokens += toks;
            }
            else if (t == MessageType.USER) {
                memUserChars += chars;
                memUserTokens += toks;
            }
            else if (t == MessageType.ASSISTANT) {
                memAssistantChars += chars;
                memAssistantTokens += toks;
            }
            else if (t == MessageType.TOOL) {
                memToolChars += chars;
                memToolTokens += toks;
            }
        }

        int toolDefTokens = 0;
        int toolDefChars = 0;
        ChatOptions options = prompt.getOptions();
        if (options instanceof ToolCallingChatOptions toolOptions) {
            List<ToolCallback> callbacks = toolOptions.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback cb : callbacks) {
                    var def = cb.getToolDefinition();
                    String block = def.name() + "\n" + nullToEmpty(def.description()) + "\n" + nullToEmpty(def.inputSchema());
                    toolDefChars += block.length();
                    toolDefTokens += ApproximateTokenEstimator.estimateTokens(block);
                }
            }
        }

        int total = systemTokens + memUserTokens + memAssistantTokens + memToolTokens + currentUserTokens + toolDefTokens;

        List<ContextCategoryRow> rows = new ArrayList<>();
        addRow(rows, "system_prompt", "System prompt", systemTokens, systemChars);
        addRow(rows, "tools", "Tool definitions (MCP)", toolDefTokens, toolDefChars);
        addRow(rows, "memory_user", "Conversation · user (history)", memUserTokens, memUserChars);
        addRow(rows, "memory_assistant", "Conversation · assistant (history)", memAssistantTokens, memAssistantChars);
        addRow(rows, "memory_tool", "Conversation · tool results (history)", memToolTokens, memToolChars);
        addRow(rows, "current_user", "Current user message", currentUserTokens, currentUserChars);

        double usedPercent = contextWindowTokens > 0 ? round1(100.0 * total / contextWindowTokens) : 0.0;

        return new ContextUsageResponse(
                ApproximateTokenEstimator.METHOD_ID,
                contextWindowTokens,
                total,
                usedPercent,
                List.copyOf(rows));
    }

    private static void addRow(
            List<ContextCategoryRow> rows,
            String category,
            String label,
            int estimatedTokens,
            int charCount) {
        if (estimatedTokens <= 0 && charCount <= 0) {
            return;
        }
        rows.add(new ContextCategoryRow(category, label, estimatedTokens, charCount > 0 ? charCount : null));
    }

    private static int findLastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getMessageType() == MessageType.USER) {
                return i;
            }
        }
        return -1;
    }

    static String fullMessagePayload(Message message) {
        return switch (message.getMessageType()) {
            case SYSTEM, USER -> nullToEmpty(safeUserSystemText(message));
            case ASSISTANT -> fullAssistant((AssistantMessage) message);
            case TOOL -> fullTool((ToolResponseMessage) message);
            default -> nullToEmpty(message.toString());
        };
    }

    private static String safeUserSystemText(Message message) {
        try {
            return nullToEmpty(message.getText());
        }
        catch (UnsupportedOperationException ex) {
            return message.toString();
        }
    }

    private static String fullAssistant(AssistantMessage message) {
        StringBuilder sb = new StringBuilder();
        sb.append(nullToEmpty(message.getText()));
        if (message.hasToolCalls()) {
            sb.append("\n[tool_calls]\n");
            for (AssistantMessage.ToolCall tc : message.getToolCalls()) {
                sb.append(tc.name())
                        .append(" id=").append(tc.id())
                        .append(" type=").append(tc.type())
                        .append("\nargs=").append(nullToEmpty(tc.arguments()))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    private static String fullTool(ToolResponseMessage message) {
        StringBuilder sb = new StringBuilder();
        for (ToolResponseMessage.ToolResponse r : message.getResponses()) {
            sb.append(r.name())
                    .append(" id=").append(r.id())
                    .append("\ndata=").append(nullToEmpty(r.responseData()))
                    .append("\n---\n");
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
