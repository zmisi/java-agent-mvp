package com.example.javaagentmvp.admissionworkflow.intent;

import com.example.javaagentmvp.rag.RagProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class AdmissionQueryHints {

    private static final List<String> DEFAULT_MAJOR_KEYWORDS = List.of(
            "计算机科学与技术", "计算机", "软件工程", "人工智能", "信息安全", "物联网工程");

    private AdmissionQueryHints() {
    }

    public record Hints(
            List<RagProperties.School> schools,
            List<String> majorKeywords,
            boolean schoolSpecified,
            boolean majorSpecified) {

        public Optional<RagProperties.School> primarySchool() {
            return schools.isEmpty() ? Optional.empty() : Optional.of(schools.get(0));
        }

        public String policyRetrievalQuery(String originalMessage) {
            StringBuilder query = new StringBuilder();
            primarySchool().ifPresent(school -> {
                if (school.displayName() != null && !school.displayName().isBlank()) {
                    query.append(school.displayName()).append(' ');
                }
            });
            query.append("招生章程 录取规则 招生政策");
            if (!majorKeywords.isEmpty()) {
                query.append(' ').append(String.join(" ", majorKeywords));
            }
            if (originalMessage != null && !originalMessage.isBlank()) {
                query.append(' ').append(originalMessage.strip());
            }
            return query.toString().strip();
        }
    }

    public static Hints parse(String message, RagProperties ragProperties) {
        String normalized = message == null ? "" : message.strip();
        List<RagProperties.School> schools = resolveSchools(normalized, ragProperties);
        List<String> majorKeywords = resolveMajorKeywords(normalized);
        return new Hints(
                schools,
                majorKeywords,
                !schools.isEmpty(),
                !majorKeywords.isEmpty());
    }

    private static List<RagProperties.School> resolveSchools(String message, RagProperties ragProperties) {
        if (message.isBlank() || ragProperties.admissions().schools().isEmpty()) {
            return List.of();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        List<RagProperties.School> matched = new ArrayList<>();
        for (RagProperties.School school : ragProperties.admissions().schools()) {
            if (matchesSchool(lower, message, school)) {
                matched.add(school);
            }
        }
        return List.copyOf(matched);
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

    private static List<String> resolveMajorKeywords(String message) {
        if (message.isBlank()) {
            return List.of();
        }
        Set<String> matched = new LinkedHashSet<>();
        for (String keyword : DEFAULT_MAJOR_KEYWORDS) {
            if (message.contains(keyword)) {
                matched.add(keyword);
            }
        }
        if (matched.contains("计算机科学与技术") && matched.contains("计算机")) {
            matched.remove("计算机");
        }
        return List.copyOf(matched);
    }
}
