package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Correlates getRankByScore MCP results for a single chat request. */
public final class McpRankContext {

    public record RankCapture(
            JsonNode rankResult,
            Integer score,
            String province,
            String formatted) {
    }

    private static final ThreadLocal<List<RankCapture>> CAPTURES = new ThreadLocal<>();

    private static final ThreadLocal<Set<String>> CAPTURED_PROVINCES = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> MULTI_PROVINCE_FAN_OUT_DONE = new ThreadLocal<>();

    private McpRankContext() {
    }

    public static boolean add(RankCapture capture) {
        if (capture == null) {
            return false;
        }
        if (hasCapturedProvince(capture.province())) {
            return false;
        }
        List<RankCapture> captures = CAPTURES.get();
        if (captures == null) {
            captures = new ArrayList<>();
            CAPTURES.set(captures);
        }
        captures.add(capture);
        markCapturedProvince(capture.province());
        return true;
    }

    public static List<RankCapture> captures() {
        List<RankCapture> captures = CAPTURES.get();
        return captures == null ? List.of() : List.copyOf(captures);
    }

    public static Optional<RankCapture> findByProvince(String province) {
        if (province == null || province.isBlank()) {
            return Optional.empty();
        }
        return captures().stream()
                .filter(capture -> province.equals(capture.province()))
                .findFirst();
    }

    public static Optional<RankCapture> capture() {
        List<RankCapture> captures = CAPTURES.get();
        if (captures == null || captures.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(captures.get(captures.size() - 1));
    }

    public static boolean hasCapturedProvince(String province) {
        if (province == null || province.isBlank()) {
            return false;
        }
        Set<String> provinces = CAPTURED_PROVINCES.get();
        return provinces != null && provinces.contains(province);
    }

    public static boolean multiProvinceFanOutDone() {
        return Boolean.TRUE.equals(MULTI_PROVINCE_FAN_OUT_DONE.get());
    }

    public static void markMultiProvinceFanOutDone() {
        MULTI_PROVINCE_FAN_OUT_DONE.set(Boolean.TRUE);
    }

    private static void markCapturedProvince(String province) {
        if (province == null || province.isBlank()) {
            return;
        }
        Set<String> provinces = CAPTURED_PROVINCES.get();
        if (provinces == null) {
            provinces = new LinkedHashSet<>();
            CAPTURED_PROVINCES.set(provinces);
        }
        provinces.add(province);
    }

    public static void clear() {
        CAPTURES.remove();
        CAPTURED_PROVINCES.remove();
        MULTI_PROVINCE_FAN_OUT_DONE.remove();
    }
}
