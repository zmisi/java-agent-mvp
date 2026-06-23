package com.example.javaagentmvp.chat.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatTableGrouper {

    private static final List<String> MAJOR_FIELD_KEYS = List.of(
            "major_name",
            "plan_count",
            "campus",
            "min_score",
            "min_rank",
            "max_score",
            "year",
            "subject_group",
            "admission_type");

    private static final List<String> META_FIELD_KEYS = List.of(
            MajorHistoryTableExpander.ROW_KIND,
            "row_kind",
            MajorHistoryTableExpander.PLAN_DELTA,
            MajorHistoryTableExpander.CAMPUS_CHANGE);

    private ChatTableGrouper() {
    }

    public static List<ChatTableGroup> groupMajorRows(List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<String, SchoolBucket> buckets = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String universityName = valueOrDash(row.get("university_name"));
            String universityCode = valueOrDash(row.get("university_code"));
            String key = universityName + "\u0000" + universityCode;
            buckets.computeIfAbsent(key, ignored -> new SchoolBucket(universityCode, universityName))
                    .majors()
                    .add(toMajorRow(row));
        }

        return buckets.values().stream()
                .map(ChatTableGrouper::toGroup)
                .sorted(Comparator.<ChatTableGroup>comparingDouble(group -> parseScore(group.minScore())).reversed())
                .toList();
    }

    public static ChatTable withGroups(ChatTable table) {
        if (table == null) {
            return null;
        }
        if (table.groups() != null && !table.groups().isEmpty()) {
            return table;
        }
        if (isRankTable(table)) {
            return table;
        }
        return new ChatTable(table.title(), table.columns(), table.rows(), groupMajorRows(table.rows()), table.province());
    }

    private static boolean isRankTable(ChatTable table) {
        if (table.columns() == null || table.columns().isEmpty()) {
            return false;
        }
        return table.columns().stream()
                .anyMatch(column -> "year_label".equals(column.key()) || "rank_range".equals(column.key()));
    }

    public static List<ChatTable> enrichTables(List<ChatTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        return tables.stream().map(ChatTableGrouper::withGroups).toList();
    }

    private static ChatTableGroup toGroup(SchoolBucket bucket) {
        List<Map<String, String>> majors = bucket.majors();
        List<Map<String, String>> currentMajors = majors.stream()
                .filter(row -> !MajorHistoryTableExpander.isHistoryRow(row))
                .sorted(Comparator.<Map<String, String>>comparingDouble(row -> parseScore(row.get("min_score"))).reversed())
                .toList();
        String minScore = currentMajors.stream()
                .map(row -> row.get("min_score"))
                .min(Comparator.comparingDouble(ChatTableGrouper::parseScore))
                .orElse("-");
        return new ChatTableGroup(
                bucket.universityCode(),
                bucket.universityName(),
                currentMajors.isEmpty() ? majors.size() : currentMajors.size(),
                minScore,
                majors);
    }

    private static Map<String, String> toMajorRow(Map<String, String> row) {
        Map<String, String> major = new LinkedHashMap<>();
        for (String key : MAJOR_FIELD_KEYS) {
            major.put(key, valueOrDash(row.get(key)));
        }
        for (String key : META_FIELD_KEYS) {
            String value = row.get(key);
            if (value != null && !value.isBlank()) {
                major.put(key, value);
            }
        }
        return major;
    }

    private static double parseScore(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(raw);
        }
        catch (NumberFormatException ex) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    private static String valueOrDash(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value;
    }

    private record SchoolBucket(String universityCode, String universityName, List<Map<String, String>> majors) {
        private SchoolBucket(String universityCode, String universityName) {
            this(universityCode, universityName, new ArrayList<>());
        }
    }
}
