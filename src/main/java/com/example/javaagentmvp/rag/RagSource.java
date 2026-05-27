package com.example.javaagentmvp.rag;

import org.springframework.ai.document.Document;

public record RagSource(String title, String source, String snippet, String school) {

    public static RagSource from(Document document) {
        String title = String.valueOf(document.getMetadata().getOrDefault("title", "unknown"));
        String source = String.valueOf(document.getMetadata().getOrDefault("source", title));
        String school = String.valueOf(document.getMetadata().getOrDefault("school", ""));
        return new RagSource(title, source, snippet(document.getText()), school);
    }

    private static String snippet(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }
}
