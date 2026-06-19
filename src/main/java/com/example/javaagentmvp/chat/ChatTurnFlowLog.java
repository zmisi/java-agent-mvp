package com.example.javaagentmvp.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.memory.ChatMemory;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Step-by-step flow log for a single chat turn ({@code n}/{@link #TOTAL_STEPS}).
 * Active only for HTTP/CLI chat paths that call {@link #begin(String, String)}.
 */
public final class ChatTurnFlowLog {

    public static final int TOTAL_STEPS = 7;

    private static final Logger log = LoggerFactory.getLogger(ChatTurnFlowLog.class);

    private static final int MAX_MESSAGE_CHARS = 240;

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private ChatTurnFlowLog() {
    }

    public enum Step {
        TURN_START(1, "接收用户消息"),
        COMPILE_IR(2, "编译招生 IR"),
        ROUTE_DECISION(3, "路由决策"),
        TASK_PROMPT(4, "注入任务上下文"),
        RAG_RETRIEVE(5, "RAG 检索"),
        MCP_PROCESS(6, "MCP 专业查询处理"),
        TURN_END(7, "回合结束");

        private final int index;
        private final String label;

        Step(int index, String label) {
            this.index = index;
            this.label = label;
        }

        public int index() {
            return index;
        }

        public String label() {
            return label;
        }
    }

    public static void begin(String conversationId, String userMessage) {
        String flowId = UUID.randomUUID().toString().substring(0, 8);
        State state = new State(flowId, conversationId == null ? "?" : conversationId, userMessage);
        CURRENT.set(state);
        state.loggedSteps.add(Step.TURN_START);
        log(state, Step.TURN_START, "message=%s", truncate(userMessage));
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static String flowId() {
        State state = CURRENT.get();
        return state == null ? "--------" : state.flowId;
    }

    public static String conversationId() {
        State state = CURRENT.get();
        return state == null ? "?" : state.conversationId;
    }

    public static void step(Step step, String detailFormat, Object... args) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        state.loggedSteps.add(step);
        log(state, step, detailFormat, args);
    }

    public static void skipped(Step step, String reasonFormat, Object... args) {
        State state = CURRENT.get();
        if (state == null || state.loggedSteps.contains(step)) {
            return;
        }
        state.loggedSteps.add(step);
        log(state, step, "skipped — " + reasonFormat, args);
    }

    public static void skippedIfAbsent(Step step, String reasonFormat, Object... args) {
        State state = CURRENT.get();
        if (state == null || state.loggedSteps.contains(step)) {
            return;
        }
        skipped(step, reasonFormat, args);
    }

    public static void end(String detailFormat, Object... args) {
        State state = CURRENT.get();
        if (state == null) {
            return;
        }
        skippedIfAbsent(Step.TASK_PROMPT, "not required for this turn");
        skippedIfAbsent(Step.RAG_RETRIEVE, "RAG path not taken");
        skippedIfAbsent(Step.MCP_PROCESS, "getMajorByScore not invoked");
        if (!state.loggedSteps.contains(Step.TURN_END)) {
            state.loggedSteps.add(Step.TURN_END);
            log(state, Step.TURN_END, detailFormat, args);
        }
        CURRENT.remove();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static String conversationId(ChatClientRequest request) {
        if (request == null || request.context() == null) {
            return "?";
        }
        Object id = request.context().get(ChatMemory.CONVERSATION_ID);
        return id == null ? "?" : String.valueOf(id);
    }

    static Set<Step> loggedStepsForTest() {
        State state = CURRENT.get();
        return state == null ? Set.of() : EnumSet.copyOf(state.loggedSteps);
    }

    private static void log(State state, Step step, String detailFormat, Object... args) {
        String detail = formatDetail(detailFormat, args);
        log.info("[CHAT flow={} conv={}] {}/{} {} — {}",
                state.flowId,
                state.conversationId,
                step.index(),
                TOTAL_STEPS,
                step.label(),
                detail);
    }

    private static String formatDetail(String format, Object... args) {
        if (format == null || format.isBlank()) {
            return "";
        }
        if (args == null || args.length == 0) {
            return format;
        }
        try {
            return String.format(format, args);
        }
        catch (RuntimeException ex) {
            return format;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').strip();
        if (normalized.length() <= MAX_MESSAGE_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    private static final class State {
        private final String flowId;
        private final String conversationId;
        private final EnumSet<Step> loggedSteps = EnumSet.noneOf(Step.class);

        private State(String flowId, String conversationId, String userMessage) {
            this.flowId = flowId;
            this.conversationId = conversationId;
        }
    }
}
