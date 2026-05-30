package com.example.javaagentmvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Generic detailed logging for model calls on dedicated ChatClient instances.
 */
public class ModelCallLoggingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ModelCallLoggingAdvisor.class);
    private static final int DEFAULT_MAX_LOG_CHARS = 4_000;

    private final String scope;
    private final int order;
    private final int maxLogChars;

    public ModelCallLoggingAdvisor(String scope) {
        this(scope, Ordered.LOWEST_PRECEDENCE - 100, DEFAULT_MAX_LOG_CHARS);
    }

    public ModelCallLoggingAdvisor(String scope, int order, int maxLogChars) {
        this.scope = scope == null || scope.isBlank() ? "MODEL" : scope.strip();
        this.order = order;
        this.maxLogChars = Math.max(256, maxLogChars);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long startedAt = System.nanoTime();
        log.info("[{}] >>> request model={}\n{}", scope, resolveModel(request), formatPrompt(request.prompt()));
        try {
            ChatClientResponse response = chain.nextCall(request);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("[{}] <<< response ({} ms)\n{}", scope, elapsedMs, formatResponse(response.chatResponse()));
            return response;
        } catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.error("[{}] <<< error ({} ms): {}", scope, elapsedMs, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public String getName() {
        return "ModelCallLoggingAdvisor-" + scope;
    }

    @Override
    public int getOrder() {
        return order;
    }

    private String resolveModel(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        if (options != null && options.getModel() != null) {
            return options.getModel();
        }
        return "(default)";
    }

    private String formatPrompt(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        List<Message> messages = prompt.getInstructions();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            sb.append("  [").append(i + 1).append("] ")
                    .append(message.getMessageType())
                    .append(": ")
                    .append(truncate(safeText(message)))
                    .append('\n');
        }
        ChatOptions options = prompt.getOptions();
        if (options != null) {
            sb.append("  options: model=").append(options.getModel())
                    .append(", temperature=").append(options.getTemperature())
                    .append(", maxTokens=").append(options.getMaxTokens());
        }
        return sb.toString();
    }

    private String formatResponse(ChatResponse chatResponse) {
        if (chatResponse == null) {
            return "  (empty response)";
        }
        StringBuilder sb = new StringBuilder();
        List<Generation> results = chatResponse.getResults();
        for (int i = 0; i < results.size(); i++) {
            Generation generation = results.get(i);
            Message output = generation.getOutput();
            sb.append("  generation[").append(i + 1).append("]: ")
                    .append(output == null ? "(null output)" : output.getMessageType() + ": " + truncate(safeText(output)));
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                sb.append("\n    finishReason=").append(generation.getMetadata().getFinishReason());
            }
            sb.append('\n');
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
        return sb.toString().stripTrailing();
    }

    private String safeText(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    private String truncate(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").strip();
        if (normalized.length() <= maxLogChars) {
            return normalized;
        }
        return normalized.substring(0, maxLogChars) + "... (" + normalized.length() + " chars total)";
    }
}
