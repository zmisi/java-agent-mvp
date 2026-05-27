package com.example.javaagentmvp.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds retrieval context grouped by configured school for multi-university admissions queries. */
final class AdmissionsGroupedContextFormatter {

    private AdmissionsGroupedContextFormatter() {
    }

    static String buildGroupedContext(List<Document> documents, List<RagProperties.School> schools) {
        Map<String, List<Document>> bySchool = groupBySchool(documents);

        StringBuilder sb = new StringBuilder();
        for (RagProperties.School school : schools) {
            appendSchoolSection(sb, school.displayName(), school.key(), bySchool.getOrDefault(school.key(), List.of()));
            bySchool.remove(school.key());
        }
        for (Map.Entry<String, List<Document>> entry : bySchool.entrySet()) {
            String label = entry.getKey().isBlank() ? "未标注学校" : entry.getKey();
            appendSchoolSection(sb, label, entry.getKey(), entry.getValue());
        }
        return sb.toString().strip();
    }

    private static Map<String, List<Document>> groupBySchool(List<Document> documents) {
        Map<String, List<Document>> bySchool = new LinkedHashMap<>();
        if (documents == null) {
            return bySchool;
        }
        for (Document document : documents) {
            String school = "";
            if (document.getMetadata() != null) {
                Object value = document.getMetadata().get("school");
                if (value != null) {
                    school = value.toString();
                }
            }
            bySchool.computeIfAbsent(school, ignored -> new ArrayList<>()).add(document);
        }
        return bySchool;
    }

    private static void appendSchoolSection(
            StringBuilder sb, String displayName, String schoolKey, List<Document> schoolDocs) {
        sb.append("## ").append(displayName);
        if (schoolKey != null && !schoolKey.isBlank()) {
            sb.append(" (").append(schoolKey).append(')');
        }
        sb.append("\n\n");
        if (schoolDocs.isEmpty()) {
            sb.append("（本次检索未命中该校录取分数/专业资料）\n\n");
            return;
        }
        for (int i = 0; i < schoolDocs.size(); i++) {
            Document document = schoolDocs.get(i);
            String source = String.valueOf(document.getMetadata() == null
                    ? "unknown"
                    : document.getMetadata().getOrDefault("source", "unknown"));
            sb.append("--- 资料 ").append(i + 1).append(" | 来源: ").append(source).append(" ---\n");
            String text = document.getText();
            sb.append(text == null ? "" : text.strip()).append("\n\n");
        }
    }
}
