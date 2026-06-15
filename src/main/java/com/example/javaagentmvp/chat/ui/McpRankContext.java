package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/** Correlates getRankByScore MCP results for a single chat request. */
public final class McpRankContext {

    public record RankCapture(
            JsonNode rankResult,
            Integer score,
            String province,
            String formatted) {
    }

    private static final ThreadLocal<RankCapture> CAPTURE = new ThreadLocal<>();

    private McpRankContext() {
    }

    public static void set(RankCapture capture) {
        if (capture == null) {
            CAPTURE.remove();
            return;
        }
        CAPTURE.set(capture);
    }

    public static Optional<RankCapture> capture() {
        return Optional.ofNullable(CAPTURE.get());
    }

    public static void clear() {
        CAPTURE.remove();
    }
}
