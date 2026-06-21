package com.example.javaagentmvp.admissionworkflow.compiler;

/**
 * Optional correlation ids for compile-time side effects (e.g. unsupported constraint logging).
 */
public final class CompileRecordContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private CompileRecordContext() {
    }

    public static void set(String conversationId, Long userId, String channel) {
        set(conversationId, userId, channel, null);
    }

    public static void set(String conversationId, Long userId, String channel, String userMessage) {
        CURRENT.set(new State(
                conversationId,
                userId,
                channel == null ? "chat" : channel,
                userMessage));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static State current() {
        return CURRENT.get();
    }

    public record State(String conversationId, Long userId, String channel, String userMessage) {
    }
}
