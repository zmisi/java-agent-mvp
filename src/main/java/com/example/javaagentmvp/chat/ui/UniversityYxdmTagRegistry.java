package com.example.javaagentmvp.chat.ui;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class UniversityYxdmTagRegistry {

    private static final List<String> TAG_PRIORITY = List.of(
            "985",
            "211",
            "双一流",
            "研究生院",
            "本科");

    private Map<String, Set<String>> tagsByYxdm = Map.of();

    @PostConstruct
    void load() {
        tagsByYxdm = loadFromClasspath();
    }

    List<String> tagsForYxdm(String yxdm) {
        if (!UniversityTagDisplay.hasUsefulText(yxdm)) {
            return List.of();
        }
        Set<String> tags = tagsByYxdm.get(yxdm.strip());
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        return sortTags(tags);
    }

    static List<String> sortTags(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>(tags);
        List<String> ordered = new ArrayList<>();
        for (String preferred : TAG_PRIORITY) {
            if (unique.remove(preferred)) {
                ordered.add(preferred);
            }
        }
        ordered.addAll(unique.stream().sorted().toList());
        return List.copyOf(ordered);
    }

    static List<String> unionTags(Collection<String> base, Collection<String> extra) {
        Set<String> merged = new LinkedHashSet<>();
        if (base != null) {
            merged.addAll(base);
        }
        if (extra != null) {
            merged.addAll(extra);
        }
        return sortTags(merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> loadFromClasspath() {
        ClassPathResource resource = new ClassPathResource("admission-ontology/university_tags.yaml");
        if (!resource.exists()) {
            return Map.of();
        }
        try (InputStream input = resource.getInputStream()) {
            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map<?, ?> root)) {
                return Map.of();
            }
            Object listsNode = root.get("lists");
            if (!(listsNode instanceof Map<?, ?> lists)) {
                return Map.of();
            }
            Map<String, Set<String>> index = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : lists.entrySet()) {
                String tag = String.valueOf(entry.getKey()).strip();
                if (tag.isEmpty() || !(entry.getValue() instanceof List<?> yxdms)) {
                    continue;
                }
                for (Object yxdmValue : yxdms) {
                    String yxdm = String.valueOf(yxdmValue).strip();
                    if (yxdm.isEmpty()) {
                        continue;
                    }
                    index.computeIfAbsent(yxdm, ignored -> new LinkedHashSet<>()).add(tag);
                }
            }
            return Map.copyOf(index);
        }
        catch (IOException ex) {
            return Map.of();
        }
    }
}
