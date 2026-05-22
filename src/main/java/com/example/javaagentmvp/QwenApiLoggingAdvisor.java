package com.example.javaagentmvp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logs each Qwen (DashScope) user turn: prompt messages, registered tools, model tool_calls,
 * TOOL results (via {@link LoggingToolCallback}), and final assistant reply.
 */
public class QwenApiLoggingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(QwenApiLoggingAdvisor.class);

    private static final ThreadLocal<Integer> ACTIVE_SESSION_ROUND = new ThreadLocal<>();

    private static final ThreadLocal<Set<String>> LOGGED_TOOL_CALL_IDS = new ThreadLocal<>();

    private final AtomicInteger sessionRound = new AtomicInteger(0);

    private final int order;

    public QwenApiLoggingAdvisor() {
        this(Ordered.LOWEST_PRECEDENCE - 100);
    }

    public QwenApiLoggingAdvisor(int order) {
        this.order = order;
    }

    /** Active session round for {@link LoggingToolCallback} correlation (same HTTP /chat request). */
    public static int activeSessionRound() {
        Integer round = ACTIVE_SESSION_ROUND.get();
        return round != null ? round : 0;
    }

    /** Per-request set of tool call ids already logged as model tool_calls. */
    static Set<String> loggedToolCallIds() {
        Set<String> ids = LOGGED_TOOL_CALL_IDS.get();
        if (ids == null) {
            ids = new HashSet<>();
            LOGGED_TOOL_CALL_IDS.set(ids);
        }
        return ids;
    }

    public void resetSessionRound() {
        sessionRound.set(0);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        int round = sessionRound.incrementAndGet();
        ACTIVE_SESSION_ROUND.set(round);
        LOGGED_TOOL_CALL_IDS.set(new HashSet<>());

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
        finally {
            ACTIVE_SESSION_ROUND.remove();
            LOGGED_TOOL_CALL_IDS.remove();
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
            sb.append("  [").append(i + 1).append("] ").append(QwenApiLogFormatting.formatMessage(messages.get(i)))
                    .append('\n');
        }
        ChatOptions options = prompt.getOptions();
        if (options != null) {
            sb.append("  options: model=").append(options.getModel())
                    .append(", temperature=").append(options.getTemperature())
                    .append(", maxTokens=").append(options.getMaxTokens());
            QwenApiLogFormatting.appendToolDefinitions(sb, options);
        }
        return sb.toString();
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
                    .append(QwenApiLogFormatting.formatMessage(output));
            if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
                sb.append("\n    finishReason=").append(generation.getMetadata().getFinishReason());
            }
            sb.append('\n');
        }
        if (chatResponse.hasToolCalls()) {
            sb.append("  hasToolCalls=true (final ChatResponse; see tool_calls/TOOL lines above)\n");
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
}
