package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * Logs {@link RagFlowLogStep#PROMPT} (request) and {@link RagFlowLogStep#ANSWER} (model reply).
 */
public class RagFlowLoggingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RagFlowLoggingAdvisor.class);

    private static final int MAX_LOG_CHARS = 4_000;

    private final int order;

    public RagFlowLoggingAdvisor() {
        this(Ordered.LOWEST_PRECEDENCE - 100);
    }

    public RagFlowLoggingAdvisor(int order) {
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        if (RagFlowContext.isSkipped()) {
            return chain.nextCall(request);
        }

        String flowId = RagFlowContext.flowId();
        long startNanos = System.nanoTime();

        log.info("[RAG {}] {} 把资料 + 问题交给大模型 — 请求 Prompt:\n{}",
                flowId, RagFlowLogStep.PROMPT, formatPrompt(request.prompt()));

        try {
            ChatClientResponse response = chain.nextCall(request);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.info("[RAG {}] {} 生成有依据的回答 — 模型返回 ({} ms):\n{}",
                    flowId, RagFlowLogStep.ANSWER, elapsedMs, formatResponse(response.chatResponse()));
            log.info("========== RAG flow end [{}] total {} ms, sources={} ==========",
                    flowId, elapsedMs, RagFlowContext.sources().size());
            return response;
        }
        catch (RuntimeException ex) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            log.error("[RAG {}] {} 模型调用失败 ({} ms): {}", flowId, RagFlowLogStep.ANSWER, elapsedMs, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public String getName() {
        return RagFlowLoggingAdvisor.class.getSimpleName();
    }

    @Override
    public int getOrder() {
        return order;
    }

    private static String formatPrompt(Prompt prompt) {
        StringBuilder sb = new StringBuilder();
        List<Message> messages = prompt.getInstructions();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            sb.append("  [").append(i + 1).append("] ")
                    .append(message.getMessageType())
                    .append(": ")
                    .append(truncate(extractText(message)))
                    .append('\n');
        }
        return sb.toString();
    }

    private static String formatResponse(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return "  (empty response)";
        }
        return "  ASSISTANT: " + truncate(chatResponse.getResult().getOutput().getText());
    }

    private static String extractText(Message message) {
        String text = message.getText();
        return text == null ? "" : text;
    }

    static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").strip();
        if (normalized.length() <= MAX_LOG_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_CHARS) + "... (" + normalized.length() + " chars total)";
    }
}
