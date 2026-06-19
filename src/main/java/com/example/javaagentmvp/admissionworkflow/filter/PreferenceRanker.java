package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPreferenceIr;
import com.example.javaagentmvp.admissionworkflow.planner.QueryPlanner;
import com.example.javaagentmvp.rag.RagSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PreferenceRanker {

    private PreferenceRanker() {
    }

    public static ObjectNode apply(
            ObjectNode payload,
            QueryConstraints constraints,
            List<RagSource> preferenceSources) {
        if (payload == null || constraints == null || !constraints.hasPreferenceRanking()) {
            return payload;
        }

        ArrayNode majors = payload.withArray("majors");
        List<ScoredMajor> scored = new ArrayList<>();
        for (JsonNode major : majors) {
            double score = scoreMajor(major, constraints, preferenceSources);
            if (major instanceof ObjectNode objectNode) {
                objectNode.put("preference_score", score);
            }
            scored.add(new ScoredMajor(major, score));
        }

        scored.sort(Comparator.comparingDouble(ScoredMajor::score).reversed());
        ArrayNode ranked = payload.arrayNode();
        scored.forEach(item -> ranked.add(item.major()));
        payload.set("majors", ranked);
        payload.put("preference_ranked", true);
        return payload;
    }

    private static double scoreMajor(
            JsonNode major,
            QueryConstraints constraints,
            List<RagSource> preferenceSources) {
        String majorName = text(major.get("major_name")).toLowerCase(Locale.ROOT);
        String universityName = text(major.get("university_name")).toLowerCase(Locale.ROOT);
        String haystack = majorName + " " + universityName;

        double totalWeight = 0.0;
        double weightedScore = 0.0;
        for (AdmissionPreferenceIr preference : constraints.preferences()) {
            double dimensionScore = dimensionScore(preference.dimension(), haystack, preferenceSources);
            weightedScore += dimensionScore * preference.weight();
            totalWeight += preference.weight();
        }
        if (totalWeight <= 0.0) {
            return 0.0;
        }
        double base = weightedScore / totalWeight;
        for (String keyword : constraints.preferenceBoostKeywords()) {
            if (haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                base = Math.min(1.0, base + 0.08);
            }
        }
        return Math.min(1.0, base);
    }

    private static double dimensionScore(
            String dimension,
            String haystack,
            List<RagSource> preferenceSources) {
        double keywordScore = 0.0;
        for (String term : QueryPlanner.preferenceTerms(dimension)) {
            if (haystack.contains(term.toLowerCase(Locale.ROOT))) {
                keywordScore = Math.max(keywordScore, 0.35);
            }
        }
        double ragScore = 0.0;
        if (preferenceSources != null) {
            for (RagSource source : preferenceSources) {
                String sourceHaystack = (source.title() + " " + source.snippet()).toLowerCase(Locale.ROOT);
                if (haystack.isBlank() || sourceHaystack.isBlank()) {
                    continue;
                }
                for (String term : QueryPlanner.preferenceTerms(dimension)) {
                    if (sourceHaystack.contains(term.toLowerCase(Locale.ROOT))
                            && haystack.chars().anyMatch(Character::isLetterOrDigit)) {
                        ragScore = Math.max(ragScore, 0.25);
                    }
                }
            }
        }
        return Math.min(1.0, 0.4 + keywordScore + ragScore);
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private record ScoredMajor(JsonNode major, double score) {
    }
}
