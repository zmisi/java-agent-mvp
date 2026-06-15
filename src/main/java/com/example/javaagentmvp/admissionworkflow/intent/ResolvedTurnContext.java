package com.example.javaagentmvp.admissionworkflow.intent;

import java.util.Optional;

/** Thread-local holder for the current request's {@link ResolvedTurn}. */
public final class ResolvedTurnContext {

    private static final ThreadLocal<ResolvedTurn> CURRENT = new ThreadLocal<>();

    private ResolvedTurnContext() {
    }

    public static void set(ResolvedTurn resolved) {
        if (resolved == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(resolved);
    }

    public static Optional<ResolvedTurn> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
