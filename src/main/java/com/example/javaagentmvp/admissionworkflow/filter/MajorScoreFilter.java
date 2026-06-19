package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MajorScoreFilter {

    private static final int SCORE_TIER_DELTA = 15;

    private MajorScoreFilter() {
    }

    public record FilterResult(
            ObjectNode payload,
            int matchedCount,
            int totalCount,
            boolean schoolFiltered,
            boolean majorFiltered,
            Map<String, Integer> tierCounts) {
    }

    public static FilterResult filter(
            JsonNode rawScoreResult,
            int userScore,
            AdmissionQueryHints.Hints hints,
            ObjectMapper objectMapper) {
        return filter(rawScoreResult, userScore, hints, null, objectMapper);
    }

    public static FilterResult filter(
            JsonNode rawScoreResult,
            int userScore,
            AdmissionQueryHints.Hints hints,
            QueryConstraints constraints,
            ObjectMapper objectMapper) {
        JsonNode majorsNode = rawScoreResult == null ? null : rawScoreResult.get("majors");
        int totalCount = rawScoreResult == null
                ? 0
                : rawScoreResult.path("count").asInt(majorsNode == null ? 0 : majorsNode.size());

        ArrayNode allMajors = objectMapper.createArrayNode();
        if (majorsNode != null && majorsNode.isArray()) {
            majorsNode.forEach(allMajors::add);
        }

        List<JsonNode> filtered = new ArrayList<>();
        for (JsonNode major : allMajors) {
            if (matchesSchool(major, hints)
                    && matchesMajor(major, hints)
                    && matchesConstraints(major, constraints)) {
                filtered.add(major);
            }
        }

        Map<String, List<JsonNode>> tiers = classifyTiers(filtered, userScore);
        Map<String, Integer> tierCounts = Map.of(
                "冲", tiers.get("冲").size(),
                "稳", tiers.get("稳").size(),
                "保", tiers.get("保").size());

        ArrayNode majorsArray = objectMapper.createArrayNode();
        filtered.forEach(majorsArray::add);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("count", filtered.size());
        payload.put("totalBeforeFilter", totalCount);
        payload.set("majors", majorsArray);
        payload.set("tier_counts", objectMapper.valueToTree(tierCounts));
        payload.set("majors_by_tier", buildMajorsByTier(tiers, objectMapper));
        if (hints.primarySchool().isPresent()) {
            payload.put("schoolKey", hints.primarySchool().get().key());
            payload.put("schoolName", hints.primarySchool().get().displayName());
        }
        if (!hints.majorKeywords().isEmpty()) {
            payload.putPOJO("majorKeywords", hints.majorKeywords());
        }
        if (constraints != null) {
            if (constraints.hasProvinceFilter()) {
                payload.putPOJO("provinces", constraints.provinces());
            }
            if (constraints.hasExclusions()) {
                payload.putPOJO("excludeSchoolNameContains", constraints.excludeSchoolNameContains());
                payload.putPOJO("excludeMajorKeywords", constraints.excludeMajorKeywords());
            }
        }

        return new FilterResult(
                payload,
                filtered.size(),
                totalCount,
                hints.schoolSpecified(),
                hints.majorSpecified() || (constraints != null && !constraints.includeMajorKeywords().isEmpty()),
                tierCounts);
    }

    private static boolean matchesSchool(JsonNode major, AdmissionQueryHints.Hints hints) {
        if (!hints.schoolSpecified()) {
            return true;
        }
        String universityCode = text(major.get("university_code"));
        String universityName = text(major.get("university_name"));
        for (RagProperties.School school : hints.schools()) {
            if (school.key() != null && school.key().equalsIgnoreCase(universityCode)) {
                return true;
            }
            if (school.displayName() != null && universityName.contains(school.displayName())) {
                return true;
            }
            for (String alias : school.aliases()) {
                if (alias != null && !alias.isBlank() && universityName.contains(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesMajor(JsonNode major, AdmissionQueryHints.Hints hints) {
        List<String> keywords = hints.majorKeywords();
        if (hints.majorSpecified()) {
            String majorName = text(major.get("major_name"));
            for (String keyword : keywords) {
                if (majorName.contains(keyword)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean matchesConstraints(JsonNode major, QueryConstraints constraints) {
        if (constraints == null) {
            return true;
        }
        String universityName = text(major.get("university_name"));
        String majorName = text(major.get("major_name"));
        String queryProvince = text(major.get("query_province"));

        if (constraints.hasProvinceFilter()) {
            boolean provinceMatched = constraints.provinces().stream().anyMatch(province ->
                    province.equals(queryProvince)
                            || universityName.contains(province)
                            || text(major.get("province")).contains(province));
            if (!provinceMatched) {
                return false;
            }
        }

        for (String token : constraints.excludeSchoolNameContains()) {
            if (!token.isBlank() && universityName.contains(token)) {
                return false;
            }
        }
        for (String token : constraints.excludeMajorKeywords()) {
            if (!token.isBlank() && majorName.contains(token)) {
                return false;
            }
        }

        if (!constraints.includeMajorKeywords().isEmpty()) {
            boolean includeMatched = false;
            for (String keyword : constraints.includeMajorKeywords()) {
                if (majorName.contains(keyword)) {
                    includeMatched = true;
                    break;
                }
            }
            if (!includeMatched) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, List<JsonNode>> classifyTiers(List<JsonNode> majors, int userScore) {
        Map<String, List<JsonNode>> tiers = new LinkedHashMap<>();
        tiers.put("冲", new ArrayList<>());
        tiers.put("稳", new ArrayList<>());
        tiers.put("保", new ArrayList<>());

        double safeThreshold = userScore - SCORE_TIER_DELTA;
        double reachUpper = userScore + SCORE_TIER_DELTA;

        for (JsonNode major : majors) {
            Double minScore = parseMinScore(major.get("min_score"));
            if (minScore == null) {
                tiers.get("稳").add(major);
                continue;
            }
            if (minScore > userScore && minScore <= reachUpper) {
                tiers.get("冲").add(major);
            }
            else if (minScore <= userScore && minScore > safeThreshold) {
                tiers.get("稳").add(major);
            }
            else if (minScore <= safeThreshold) {
                tiers.get("保").add(major);
            }
        }
        return tiers;
    }

    private static ObjectNode buildMajorsByTier(Map<String, List<JsonNode>> tiers, ObjectMapper objectMapper) {
        ObjectNode node = objectMapper.createObjectNode();
        tiers.forEach((tier, majors) -> {
            ArrayNode array = objectMapper.createArrayNode();
            majors.forEach(array::add);
            node.set(tier, array);
        });
        return node;
    }

    private static Double parseMinScore(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return Double.parseDouble(node.asText().strip());
        }
        catch (NumberFormatException ex) {
            return node.isNumber() ? node.asDouble() : null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }
}
