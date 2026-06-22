package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.compiler.IncludeSchoolSupport;
import com.example.javaagentmvp.rag.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Filters MCP tier payloads ({@code majors_by_tier}) by IR include school/major constraints. */
public final class MajorTierResultFilter {

    private static final List<String> TIERS = List.of("冲", "稳", "保");

    private MajorTierResultFilter() {
    }

    public static boolean needsFiltering(AdmissionQueryIr query, RagProperties ragProperties) {
        if (query == null) {
            return false;
        }
        return !IncludeSchoolSupport.effectiveIncludeSchools(query, ragProperties).isEmpty()
                || !query.filters().includeMajorKeywords().isEmpty();
    }

    public static ObjectNode filter(
            JsonNode raw,
            AdmissionQueryIr query,
            RagProperties ragProperties,
            ObjectMapper objectMapper) {
        if (raw == null || !raw.isObject()) {
            return objectMapper.createObjectNode();
        }
        List<String> includeSchools = IncludeSchoolSupport.effectiveIncludeSchools(query, ragProperties);
        List<String> includeMajors = query.filters().includeMajorKeywords();
        boolean schoolFilter = !includeSchools.isEmpty();
        boolean majorFilter = !includeMajors.isEmpty();

        ObjectNode payload = ((ObjectNode) raw).deepCopy();
        ObjectNode majorsByTier = objectMapper.createObjectNode();
        ArrayNode allMajors = objectMapper.createArrayNode();
        ObjectNode tierCounts = objectMapper.createObjectNode();

        for (String tier : TIERS) {
            ArrayNode bucket = objectMapper.createArrayNode();
            JsonNode source = payload.path("majors_by_tier").path(tier);
            if (source.isArray()) {
                for (JsonNode major : source) {
                    if (matches(major, includeSchools, schoolFilter, includeMajors, majorFilter)) {
                        bucket.add(major);
                        allMajors.add(major);
                    }
                }
            }
            majorsByTier.set(tier, bucket);
            tierCounts.put(tier, bucket.size());
        }

        payload.set("majors_by_tier", majorsByTier);
        payload.set("tier_counts", tierCounts);
        payload.set("majors", allMajors);
        payload.put("count", allMajors.size());
        return payload;
    }

    private static boolean matches(
            JsonNode major,
            List<String> includeSchools,
            boolean schoolFilter,
            List<String> includeMajors,
            boolean majorFilter) {
        if (schoolFilter && !matchesIncludeSchool(major, includeSchools)) {
            return false;
        }
        if (majorFilter && !matchesIncludeMajor(major, includeMajors)) {
            return false;
        }
        return true;
    }

    private static boolean matchesIncludeSchool(JsonNode major, List<String> includeSchools) {
        String universityName = text(major.get("university_name"));
        String universityCode = text(major.get("university_code"));
        for (String token : includeSchools) {
            if (token.isBlank()) {
                continue;
            }
            if (universityName.contains(token) || universityCode.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIncludeMajor(JsonNode major, List<String> includeMajors) {
        String majorName = text(major.get("major_name"));
        for (String keyword : includeMajors) {
            if (!keyword.isBlank() && majorName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }
}
