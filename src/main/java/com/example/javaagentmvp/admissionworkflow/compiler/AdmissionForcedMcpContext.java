package com.example.javaagentmvp.admissionworkflow.compiler;

import java.util.Optional;

/** Thread-local state for Chat turns where MCP was pre-executed from compiled IR. */
public final class AdmissionForcedMcpContext {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private AdmissionForcedMcpContext() {
    }

    public record State(
            boolean preExecuted,
            String task,
            String lastToolResponse) {
    }

    public static void markPreExecuted(String task, String lastToolResponse) {
        CURRENT.set(new State(true, task, lastToolResponse));
    }

    public static boolean isPreExecuted() {
        return Optional.ofNullable(CURRENT.get()).map(State::preExecuted).orElse(false);
    }

    public static Optional<String> lastToolResponse() {
        return Optional.ofNullable(CURRENT.get()).map(State::lastToolResponse);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
