package com.example.javaagentmvp.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionsGroupedContextFormatterTest {

    @Test
    void groupsChunksBySchoolInConfiguredOrder() {
        List<RagProperties.School> schools = List.of(
                new RagProperties.School("hfut", "合肥工业大学", List.of("合工大"), List.of("/hfut/")),
                new RagProperties.School("hfuu", "合肥大学", List.of("合肥大学"), List.of("/hfuu/")));

        List<Document> documents = List.of(
                new Document("hfuu score line", Map.of("school", "hfuu", "source", "hfuu-scores.md")),
                new Document("hfut score line", Map.of("school", "hfut", "source", "hfut-scores.md")));

        String grouped = AdmissionsGroupedContextFormatter.buildGroupedContext(documents, schools);

        int hfutIndex = grouped.indexOf("## 合肥工业大学");
        int hfuuIndex = grouped.indexOf("## 合肥大学");
        assertTrue(hfutIndex >= 0);
        assertTrue(hfuuIndex >= 0);
        assertTrue(hfutIndex < hfuuIndex);
        assertTrue(grouped.contains("hfut-scores.md"));
        assertTrue(grouped.contains("hfuu-scores.md"));
    }

    @Test
    void notesMissingSchoolWhenNoChunksRetrieved() {
        List<RagProperties.School> schools = List.of(
                new RagProperties.School("hfut", "合肥工业大学", List.of(), List.of()),
                new RagProperties.School("hfuu", "合肥大学", List.of(), List.of()));

        String grouped = AdmissionsGroupedContextFormatter.buildGroupedContext(List.of(), schools);

        assertTrue(grouped.contains("## 合肥工业大学"));
        assertTrue(grouped.contains("## 合肥大学"));
        assertTrue(grouped.contains("未命中该校录取分数"));
    }
}
