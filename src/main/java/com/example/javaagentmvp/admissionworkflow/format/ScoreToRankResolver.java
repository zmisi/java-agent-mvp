package com.example.javaagentmvp.admissionworkflow.format;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Comparator;
import java.util.OptionalInt;

/** Derives a single admission rank from {@code getRankByScore} MCP output for {@code getMajorByRank}. */
public final class ScoreToRankResolver {

    private ScoreToRankResolver() {
    }

    /**
     * Picks the best matching rank row (prefer requested year, else latest year) and returns
     * {@code rank} when present, otherwise {@code rank_min}.
     */
    public static OptionalInt resolveRank(JsonNode rankByScoreResult, Integer year, String subjectGroup) {
        if (rankByScoreResult == null || !rankByScoreResult.has("ranks") || !rankByScoreResult.get("ranks").isArray()) {
            return OptionalInt.empty();
        }
        JsonNode ranks = rankByScoreResult.get("ranks");
        if (ranks.isEmpty()) {
            return OptionalInt.empty();
        }

        JsonNode best = null;
        int bestYear = Integer.MIN_VALUE;
        for (JsonNode row : ranks) {
            if (!matchesSubjectGroup(row, subjectGroup)) {
                continue;
            }
            int rowYear = row.path("year").asInt(0);
            if (year != null && rowYear != year) {
                continue;
            }
            if (best == null || rowYear > bestYear) {
                best = row;
                bestYear = rowYear;
            }
        }

        if (best == null && year != null) {
            for (JsonNode row : ranks) {
                if (!matchesSubjectGroup(row, subjectGroup)) {
                    continue;
                }
                int rowYear = row.path("year").asInt(0);
                if (best == null || rowYear > bestYear) {
                    best = row;
                    bestYear = rowYear;
                }
            }
        }

        if (best == null) {
            best = RankResponseFormatter.sortedRankRows(ranks).stream()
                    .max(Comparator.comparingInt(row -> row.path("year").asInt(0)))
                    .orElse(null);
        }
        if (best == null) {
            return OptionalInt.empty();
        }
        return rankFromRow(best);
    }

    private static OptionalInt rankFromRow(JsonNode row) {
        if (row == null || row.isNull()) {
            return OptionalInt.empty();
        }
        JsonNode rankNode = row.get("rank");
        if (rankNode != null && rankNode.isNumber() && rankNode.asInt() > 0) {
            return OptionalInt.of(rankNode.asInt());
        }
        JsonNode rankMin = row.get("rank_min");
        if (rankMin != null && rankMin.isNumber() && rankMin.asInt() > 0) {
            return OptionalInt.of(rankMin.asInt());
        }
        return OptionalInt.empty();
    }

    private static boolean matchesSubjectGroup(JsonNode row, String requested) {
        if (requested == null || requested.isBlank()) {
            return true;
        }
        String resolved = RankSubjectGroupResolver.rankSubjectGroupForProvince(
                row.path("province").asText(null),
                requested);
        if (resolved == null || resolved.isBlank()) {
            return true;
        }
        String rowGroup = row.path("subject_group").asText("").strip();
        if (rowGroup.isBlank()) {
            return true;
        }
        return rowGroup.contains(resolved) || resolved.contains(rowGroup) || rowGroup.startsWith(resolved.substring(0, 1));
    }
}
