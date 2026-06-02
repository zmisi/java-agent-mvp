package com.example.javaagentmvp.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrievalServiceTest {

    @Test
    void followUpCarriesPriorAdmissionsIntentIntoRetrievalQuery() {
        VectorStore vectorStore = mock(VectorStore.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("charter", Map.of("source", "rag-docs/hfut/charters/2025/章程.md", "distance", 0.10)),
                new Document("plan", Map.of("source", "rag-docs/hfut/plans/2025/安徽/计划.md", "distance", 0.30)),
                new Document("score", Map.of("source", "rag-docs/hfut/scores/2025/安徽/分数.md", "distance", 0.25))));

        RagRetrievalService service = new RagRetrievalService(
                vectorStore, jdbcTemplate, new ObjectMapper(), testProperties());

        List<Document> documents = service.search(
                "仅合肥工业大学",
                List.of("介绍下计算机专业"),
                List.of("根据前文讨论机械与计算机专业的招生计划继续说明"));

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(requestCaptor.capture());
        String actualQuery = extractStringProperty(requestCaptor.getValue(), "query");
        assertTrue(actualQuery.contains("仅合肥工业大学"));
        assertTrue(actualQuery.contains("专业"));

        Set<String> chosenSources = new HashSet<>();
        documents.forEach(doc -> chosenSources.add(String.valueOf(doc.getMetadata().get("source"))));
        assertEquals(2, documents.size());
        assertTrue(chosenSources.contains("rag-docs/hfut/charters/2025/章程.md"));
        assertTrue(chosenSources.contains("rag-docs/hfut/plans/2025/安徽/计划.md"));
    }

    private static String extractStringProperty(Object target, String propertyName) {
        try {
            Method method = target.getClass().getMethod(propertyName);
            Object value = method.invoke(target);
            return value == null ? "" : value.toString();
        }
        catch (Exception ignored) {
            // fallback to bean getter / field
        }
        try {
            Method getter = target.getClass().getMethod("get" + Character.toUpperCase(propertyName.charAt(0))
                    + propertyName.substring(1));
            Object value = getter.invoke(target);
            return value == null ? "" : value.toString();
        }
        catch (Exception ignored) {
            // fallback to field
        }
        try {
            Field field = target.getClass().getDeclaredField(propertyName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? "" : value.toString();
        }
        catch (Exception ex) {
            throw new IllegalStateException("Cannot read property '" + propertyName + "' from " + target.getClass(), ex);
        }
    }

    private static RagProperties testProperties() {
        return new RagProperties(
                true,
                false,
                false,
                "agent_ui",
                "rag_vector_store",
                "classpath:/rag-docs/**/*.md",
                2,
                0.70,
                true,
                "",
                new RagProperties.Routing(List.of(
                        "\\brag\\b",
                        "招生简章|招生章程|招生计划"),
                        List.of("\\b(select|from|where)\\b")),
                new RagProperties.Admissions(
                        true,
                        List.of("招生简章", "招生章程", "章程", "简章", "政策", "规则", "专项", "转专业", "体检", "投档", "招生计划"),
                        4,
                        12,
                        List.of(
                                new RagProperties.School(
                                        "hfut",
                                        "合肥工业大学",
                                        List.of("合工大", "合肥工业大学", "HFUT"),
                                        List.of("/hfut/", "hfut/"))),
                        ""),
                new RagProperties.Hybrid(true, 2, 3, 3, 60, 1.0, 0.9, "auto", "simple"));
    }
}
