package com.example.javaagentmvp.admissionworkflow;

public record AsyncProperties(
        boolean enabled,
        String queueKey,
        boolean consumerEnabled,
        int brpopTimeoutSeconds) {

    public static AsyncProperties defaults() {
        return new AsyncProperties(false, "admission-workflow:jobs", true, 5);
    }

    public AsyncProperties {
        if (queueKey == null || queueKey.isBlank()) {
            queueKey = "admission-workflow:jobs";
        }
        if (brpopTimeoutSeconds <= 0) {
            brpopTimeoutSeconds = 5;
        }
    }
}
