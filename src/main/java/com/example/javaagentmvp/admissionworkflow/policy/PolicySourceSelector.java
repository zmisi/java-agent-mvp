package com.example.javaagentmvp.admissionworkflow.policy;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PolicySourceSelector {

    private PolicySourceSelector() {
    }

    public static List<RagSource> refine(List<RagSource> sources, AdmissionQueryHints.Hints hints) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        List<RagSource> filtered = new ArrayList<>(sources);
        if (hints.schoolSpecified()) {
            filtered = filtered.stream()
                    .filter(source -> matchesSchool(source, hints))
                    .toList();
        }
        if (filtered.isEmpty()) {
            filtered = new ArrayList<>(sources);
        }
        return dedupeBySource(filtered).stream()
                .sorted(Comparator.comparingInt(PolicySourceSelector::charterPriority).reversed())
                .limit(8)
                .toList();
    }

    private static List<RagSource> dedupeBySource(List<RagSource> sources) {
        Map<String, RagSource> unique = new LinkedHashMap<>();
        for (RagSource source : sources) {
            String key = source.source() == null || source.source().isBlank()
                    ? source.title()
                    : source.source();
            unique.putIfAbsent(key, source);
        }
        return List.copyOf(unique.values());
    }

    private static boolean matchesSchool(RagSource source, AdmissionQueryHints.Hints hints) {
        String schoolMeta = source.school() == null ? "" : source.school().toLowerCase(Locale.ROOT);
        String sourcePath = (source.source() + " " + source.title()).toLowerCase(Locale.ROOT);
        for (RagProperties.School school : hints.schools()) {
            if (school.key() != null && school.key().equalsIgnoreCase(schoolMeta)) {
                return true;
            }
            if (school.displayName() != null
                    && sourcePath.contains(school.displayName().toLowerCase(Locale.ROOT))) {
                return true;
            }
            for (String alias : school.aliases()) {
                if (alias != null && !alias.isBlank() && sourcePath.contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            for (String pathPart : school.pathContains()) {
                if (pathPart != null && !pathPart.isBlank()
                        && sourcePath.contains(pathPart.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int charterPriority(RagSource source) {
        String combined = (source.source() + " " + source.title()).toLowerCase(Locale.ROOT);
        if (combined.contains("/charters/") || combined.contains("charters")
                || combined.contains("章程") || combined.contains("简章")) {
            return 3;
        }
        if (combined.contains("/plans/") || combined.contains("计划")) {
            return 2;
        }
        if (combined.contains("/scores/") || combined.contains("分数")) {
            return 1;
        }
        return 0;
    }
}
