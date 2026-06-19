package com.example.javaagentmvp.admissionworkflow.compiler;

import java.util.Optional;

/** Thread-local holder for the current chat turn's compiled {@link AdmissionQueryIr}. */
public final class AdmissionQueryContext {

    private static final ThreadLocal<AdmissionQueryIr> CURRENT = new ThreadLocal<>();

    private AdmissionQueryContext() {
    }

    public static void set(AdmissionQueryIr query) {
        if (query == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(query);
    }

    public static Optional<AdmissionQueryIr> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
