package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.rag.RagProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Resolves user-mentioned schools into {@code filters.include_schools} values. */
public final class IncludeSchoolSupport {

    private IncludeSchoolSupport() {
    }

    public static List<String> matchIncludeSchools(String message, RagProperties ragProperties) {
        if (message == null || message.isBlank() || ragProperties == null) {
            return List.of();
        }
        List<RagProperties.School> schools = ragProperties.admissions().schools();
        if (schools.isEmpty()) {
            return List.of();
        }
        String normalized = message.strip();
        String lower = normalized.toLowerCase(Locale.ROOT);
        Set<String> matched = new LinkedHashSet<>();
        for (RagProperties.School school : schools) {
            if (matchesSchool(lower, normalized, school)) {
                String displayName = school.displayName();
                if (displayName != null && !displayName.isBlank()) {
                    matched.add(displayName.strip());
                }
            }
        }
        return List.copyOf(matched);
    }

    /**
     * Schools to narrow MCP tier results: validated {@code include_schools} from IR, or fresh parse from message.
     */
    public static List<String> effectiveIncludeSchools(AdmissionQueryIr query, RagProperties ragProperties) {
        if (query == null) {
            return List.of();
        }
        String message = query.rawMessage() == null ? "" : query.rawMessage();
        List<String> validated = new ArrayList<>();
        for (String school : query.filters().includeSchools()) {
            if (school != null && !school.isBlank() && isSchoolTokenMentioned(message, school, ragProperties)) {
                validated.add(school.strip());
            }
        }
        if (!validated.isEmpty()) {
            return List.copyOf(validated);
        }
        return matchIncludeSchools(message, ragProperties);
    }

    public static boolean isSchoolTokenMentioned(
            String message,
            String schoolToken,
            RagProperties ragProperties) {
        if (message == null || message.isBlank() || schoolToken == null || schoolToken.isBlank()) {
            return false;
        }
        if (message.contains(schoolToken.strip())) {
            return true;
        }
        return findSchool(schoolToken, ragProperties)
                .map(school -> matchesSchool(
                        message.toLowerCase(Locale.ROOT),
                        message,
                        school))
                .orElse(false);
    }

    private static Optional<RagProperties.School> findSchool(String token, RagProperties ragProperties) {
        if (ragProperties == null || token == null || token.isBlank()) {
            return Optional.empty();
        }
        String stripped = token.strip();
        for (RagProperties.School school : ragProperties.admissions().schools()) {
            if (stripped.equalsIgnoreCase(school.key())
                    || stripped.equals(school.displayName())) {
                return Optional.of(school);
            }
            for (String alias : school.aliases()) {
                if (stripped.equals(alias)) {
                    return Optional.of(school);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean matchesSchool(String lowerMessage, String originalMessage, RagProperties.School school) {
        if (school.displayName() != null && !school.displayName().isBlank()) {
            if (lowerMessage.contains(school.displayName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (String alias : school.aliases()) {
            if (alias != null && !alias.isBlank() && originalMessage.contains(alias)) {
                return true;
            }
        }
        return false;
    }
}
