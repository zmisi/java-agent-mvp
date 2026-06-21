package com.example.javaagentmvp.admissionworkflow.compiler;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AdmissionOntologyRegistry {

    private Map<String, List<String>> regions = Map.of();
    private Map<String, Map<String, List<String>>> exclusions = Map.of();
    private List<PreferencePhrase> preferencePhrases = List.of();
    private List<UnsupportedSignalPhrase> unsupportedSignalPhrases = List.of();
    private List<MajorCategoryPhrase> majorCategoryPhrases = List.of();

    record PreferencePhrase(String phrase, String dimension) {
    }

    record UnsupportedSignalPhrase(String phrase, String constraintType, String reason, String label) {
    }

    record MajorCategoryPhrase(
            String phrase,
            String key,
            List<String> disciplineGroups,
            List<String> disciplineCategories,
            String label) {
    }

    public record MajorCategoryMatch(
            List<String> disciplineGroups,
            List<String> disciplineCategories,
            List<String> matchedPhrases) {
    }

    @PostConstruct
    public void load() throws IOException {
        regions = loadRegions();
        exclusions = loadExclusions();
        preferencePhrases = loadPreferencePhrases();
        unsupportedSignalPhrases = loadUnsupportedSignalPhrases();
        majorCategoryPhrases = loadMajorCategoryPhrases();
    }

    public List<AdmissionRegionIr> matchRegions(String message) {
        List<AdmissionRegionIr> matched = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        regions.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(phrase -> {
                    if (message.contains(phrase) && seen.add(phrase)) {
                        matched.add(new AdmissionRegionIr(phrase, List.copyOf(regions.get(phrase))));
                    }
                });
        return matched;
    }

    public AdmissionFiltersIr matchExclusions(String message) {
        List<String> excludeSchools = new ArrayList<>();
        List<String> excludeMajors = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<String>>> entry : sortedExclusions()) {
            if (!message.contains(entry.getKey())) {
                continue;
            }
            mergeUnique(excludeSchools, entry.getValue().get("exclude_school_name_contains"));
            mergeUnique(excludeMajors, entry.getValue().get("exclude_major_keywords"));
        }
        return new AdmissionFiltersIr(excludeSchools, excludeMajors, List.of(), List.of(), List.of(), List.of());
    }

    public List<AdmissionPreferenceIr> matchPreferences(String message) {
        List<AdmissionPreferenceIr> preferences = new ArrayList<>();
        Set<String> seenDimensions = new LinkedHashSet<>();
        for (PreferencePhrase candidate : preferencePhrases) {
            if (!message.contains(candidate.phrase()) || !seenDimensions.add(candidate.dimension())) {
                continue;
            }
            preferences.add(new AdmissionPreferenceIr(candidate.dimension(), 1.0, candidate.phrase()));
        }
        if (preferences.size() > 1) {
            double weight = 1.0 / preferences.size();
            preferences = preferences.stream()
                    .map(pref -> new AdmissionPreferenceIr(pref.dimension(), weight, pref.rawPhrase()))
                    .toList();
        }
        return preferences;
    }

    public List<UnsupportedConstraintIr> matchUnsupportedConstraints(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<UnsupportedConstraintIr> matched = new ArrayList<>();
        Set<String> seenTypes = new LinkedHashSet<>();
        for (UnsupportedSignalPhrase candidate : unsupportedSignalPhrases) {
            if (!message.contains(candidate.phrase()) || !seenTypes.add(candidate.constraintType())) {
                continue;
            }
            matched.add(new UnsupportedConstraintIr(
                    candidate.phrase(),
                    candidate.constraintType(),
                    candidate.reason(),
                    candidate.label()));
        }
        return List.copyOf(matched);
    }

    public MajorCategoryMatch matchMajorCategoryFilters(String message) {
        if (message == null || message.isBlank()) {
            return new MajorCategoryMatch(List.of(), List.of(), List.of());
        }
        List<String> groups = new ArrayList<>();
        List<String> categories = new ArrayList<>();
        List<String> matchedPhrases = new ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();
        for (MajorCategoryPhrase candidate : majorCategoryPhrases) {
            if (!message.contains(candidate.phrase()) || !seenKeys.add(candidate.key())) {
                continue;
            }
            matchedPhrases.add(candidate.phrase());
            mergeUnique(groups, candidate.disciplineGroups());
            mergeUnique(categories, candidate.disciplineCategories());
        }
        return new MajorCategoryMatch(List.copyOf(groups), List.copyOf(categories), List.copyOf(matchedPhrases));
    }

    private List<Map.Entry<String, Map<String, List<String>>>> sortedExclusions() {
        return exclusions.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> -entry.getKey().length()))
                .toList();
    }

    private static void mergeUnique(List<String> target, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (!target.contains(value)) {
                target.add(value);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> loadRegions() throws IOException {
        Map<String, Object> root = loadYaml("admission-ontology/regions.yaml");
        Map<String, List<String>> loaded = new LinkedHashMap<>();
        Object regionsNode = root.get("regions");
        if (regionsNode instanceof Map<?, ?> regionsMap) {
            regionsMap.forEach((phrase, body) -> {
                if (body instanceof Map<?, ?> bodyMap && bodyMap.get("provinces") instanceof List<?> provinces) {
                    loaded.put(String.valueOf(phrase), provinces.stream().map(String::valueOf).toList());
                }
            });
        }
        return Map.copyOf(loaded);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, List<String>>> loadExclusions() throws IOException {
        Map<String, Object> root = loadYaml("admission-ontology/exclusions.yaml");
        Map<String, Map<String, List<String>>> loaded = new LinkedHashMap<>();
        Object exclusionsNode = root.get("exclusions");
        if (exclusionsNode instanceof Map<?, ?> exclusionsMap) {
            exclusionsMap.forEach((phrase, body) -> {
                if (body instanceof Map<?, ?> bodyMap) {
                    Map<String, List<String>> normalized = new LinkedHashMap<>();
                    bodyMap.forEach((key, values) -> {
                        if (values instanceof List<?> list) {
                            normalized.put(String.valueOf(key), list.stream().map(String::valueOf).toList());
                        }
                    });
                    loaded.put(String.valueOf(phrase), Map.copyOf(normalized));
                }
            });
        }
        return Map.copyOf(loaded);
    }

    @SuppressWarnings("unchecked")
    private List<PreferencePhrase> loadPreferencePhrases() throws IOException {
        Map<String, Object> root = loadYaml("admission-ontology/preferences.yaml");
        List<PreferencePhrase> loaded = new ArrayList<>();
        Object preferencesNode = root.get("preferences");
        if (preferencesNode instanceof Map<?, ?> preferencesMap) {
            preferencesMap.forEach((phrase, body) -> {
                if (body instanceof Map<?, ?> bodyMap) {
                    String dimension = String.valueOf(bodyMap.get("dimension"));
                    loaded.add(new PreferencePhrase(String.valueOf(phrase), dimension));
                    Object aliases = bodyMap.get("aliases");
                    if (aliases instanceof List<?> aliasList) {
                        aliasList.forEach(alias -> loaded.add(new PreferencePhrase(String.valueOf(alias), dimension)));
                    }
                }
            });
        }
        return loaded.stream()
                .sorted(Comparator.comparingInt(phrase -> -phrase.phrase().length()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<UnsupportedSignalPhrase> loadUnsupportedSignalPhrases() throws IOException {
        Map<String, Object> root = loadYaml("admission-ontology/unsupported_signals.yaml");
        List<UnsupportedSignalPhrase> loaded = new ArrayList<>();
        Object signalsNode = root.get("unsupported_signals");
        if (signalsNode instanceof Map<?, ?> signalsMap) {
            signalsMap.forEach((phrase, body) -> {
                if (body instanceof Map<?, ?> bodyMap) {
                    String constraintType = String.valueOf(bodyMap.get("constraint_type"));
                    Object reasonObj = bodyMap.get("reason");
                    String reason = reasonObj == null ? "no_data" : String.valueOf(reasonObj);
                    Object labelObj = bodyMap.get("label");
                    String label = labelObj == null ? String.valueOf(phrase) : String.valueOf(labelObj);
                    loaded.add(new UnsupportedSignalPhrase(String.valueOf(phrase), constraintType, reason, label));
                    Object aliases = bodyMap.get("aliases");
                    if (aliases instanceof List<?> aliasList) {
                        aliasList.forEach(alias -> loaded.add(
                                new UnsupportedSignalPhrase(
                                        String.valueOf(alias),
                                        constraintType,
                                        reason,
                                        label)));
                    }
                }
            });
        }
        return loaded.stream()
                .sorted(Comparator.comparingInt(phrase -> -phrase.phrase().length()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<MajorCategoryPhrase> loadMajorCategoryPhrases() throws IOException {
        Map<String, Object> root = loadYaml("admission-ontology/major_category_filters.yaml");
        List<MajorCategoryPhrase> loaded = new ArrayList<>();
        Object filtersNode = root.get("major_category_filters");
        if (filtersNode instanceof Map<?, ?> filtersMap) {
            filtersMap.forEach((key, body) -> {
                if (!(body instanceof Map<?, ?> bodyMap)) {
                    return;
                }
                String filterKey = String.valueOf(key);
                List<String> groups = readStringList(bodyMap.get("discipline_groups"));
                List<String> categories = readStringList(bodyMap.get("discipline_categories"));
                Object labelObj = bodyMap.get("label");
                String label = labelObj == null ? filterKey : String.valueOf(labelObj);
                loaded.add(new MajorCategoryPhrase(filterKey, filterKey, groups, categories, label));
                Object aliases = bodyMap.get("aliases");
                if (aliases instanceof List<?> aliasList) {
                    aliasList.forEach(alias -> loaded.add(new MajorCategoryPhrase(
                            String.valueOf(alias),
                            filterKey,
                            groups,
                            categories,
                            label)));
                }
            });
        }
        return loaded.stream()
                .sorted(Comparator.comparingInt(phrase -> -phrase.phrase().length()))
                .toList();
    }

    private static List<String> readStringList(Object node) {
        if (!(node instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static Map<String, Object> loadYaml(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream input = resource.getInputStream()) {
            Object loaded = new Yaml().load(input);
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Map.of();
        }
    }
}
