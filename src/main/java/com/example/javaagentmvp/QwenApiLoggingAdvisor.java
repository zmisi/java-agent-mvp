package com.example.javaagentmvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Logs each Qwen (DashScope) round-trip during {@link org.springframework.ai.chat.client.ChatClient} calls,
 * including prompt messages, tool calls, token usage, and latency.
 */
public class QwenApiLoggingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(QwenApiLoggingAdvisor.class);

    private static final int MAX_TEXT_LENGTH = 2_000;

    private final AtomicInteger sessionRound = new AtomicInteger(0);

    private final int order;

    /**
     * Runs after {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}
     * so logs include conversation history injected into the prompt.
     */
    public QwenApiLoggingAdvisor() {
        this(Ordered.LOWEST_PRECEDENCE - 100);
    }

    public QwenApiLoggingAdvisor(int order) {
        this.order = order;
    }

    /** Reset round counter before each user question in the REPL. */
    public void resetSessionRound() {
        sessionRound.set(0);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        int round = sessionRound.incrementAndGet();
        long startNanos = System.nanoTime();

        log.info(">>> Qwen request [session round {}] model={}\n{}",
                round, resolveModel(request), formatPrompt(request));

        try {
            ChatClientResponse response = chain.nextCall(request);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("<<< Qwen response [session round {}, {} ms]\n{}",
                    round, elapsedMs, formatChatResponse(response.chatResponse()));
            return response;
        }
        catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("<<< Qwen error [session round {}, {} ms]: {}",
                    round, elapsedMs, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public String getName() {
        return QwenApiLoggingAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    private static String resolveModel(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        if (options != null && options.getModel() != null) {
            return options.getModel();
        }
        return "qwen (from spring.ai.dashscope)";
    }

    private static String formatPrompt(ChatClientRequest request) {
        Prompt prompt = request.prompt();
        StringBuilder sb = new StringBuilder();
        List<Message> messages = prompt.getInstructions();
        for (int i = 0; i < messages.size(); i++) {
            sb.append("  [").append(i + 1).append("] ").append(formatMessage(messages.get(i))).append('\n');
        }
        ChatOptions options = prompt.getOptions();
        if (options != null) {
            sb.append("  options: model=").append(options.getModel())
                    .append(", temperature=").append(options.getTemperature())
                    .append(", maxTokens=").append(options.getMaxTokens());
        }
        return sb.toString();
    }

    private static String formatMessage(Message message) {
        return switch (message.getMessageType()) {
            case SYSTEM -> "SYSTEM: " + truncate(extractText(message));
            case USER -> "USER: " + truncate(extractText(message));
            case ASSISTANT -> formatAssistant((AssistantMessage) message);
            case TOOL -> formatToolResponse((ToolResponseMessage) message);
            default -> message.getMessageType() + ": " + truncate(message.toString());
        };
    }

    private static String formatAssistant(AssistantMessage message) {
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

    private static String formatToolResponse(ToolResponseMessage message) {
        String responses = message.getResponses().stream()
                .map(r -> "id=" + r.id() + ", name=" + r.name() + ", data=" + truncate(r.responseData()))
                .collect(Collectors.joining("; "));
        return "TOOL: " + responses;
    }

    private static String formatChatResponse(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return "  (empty response)";
        }
        StringBuilder sb = new StringBuilder();
        List<Generation> results = chatResponse.getResults();
        for (int i = 0; i < results.size(); i++) {
            Generation generation = results.get(i);
            Message output = generation.getOutput();
            sb.append("  generation[").append(i + 1).append("]: ")
                    .append(formatMessage(output));
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                sb.append("\n    finishReason=").append(generation.getMetadata().getFinishReason());
            }
            sb.append('\n');
        }
        if (chatResponse.hasToolCalls()) {
            sb.append("  hasToolCalls=true\n");
        }
        ChatResponseMetadata metadata = chatResponse.getMetadata();
        if (metadata != null) {
            if (metadata.getModel() != null) {
                sb.append("  model=").append(metadata.getModel()).append('\n');
            }
            Usage usage = metadata.getUsage();
            if (usage != null) {
                sb.append("  usage: promptTokens=").append(usage.getPromptTokens())
                        .append(", completionTokens=").append(usage.getCompletionTokens())
                        .append(", totalTokens=").append(usage.getTotalTokens())
                        .append('\n');
            }
        }
        return sb.toString();
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

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.length() <= MAX_TEXT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_TEXT_LENGTH) + "... (" + normalized.length() + " chars total)";
    }
}
