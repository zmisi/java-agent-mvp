package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final VectorStore vectorStore;

    private final RagProperties ragProperties;

    public RagRetrievalService(VectorStore ragVectorStore, RagProperties ragProperties) {
        this.vectorStore = ragVectorStore;
        this.ragProperties = ragProperties;
    }

    public List<Document> search(String message) {
        String flowId = RagFlowContext.flowId();
        String normalized = message == null ? "" : message.strip();

        if (shouldFanOutAdmissions(normalized)) {
            List<Document> documents = fanOutAdmissionsSearch(flowId, normalized);
            RagFlowContext.beginMultiSchoolAdmissions(documents);
            return documents;
        }

        return singleSearch(flowId, normalized, ragProperties.topK(), "检索相关资料");
    }

    private List<Document> singleSearch(String flowId, String query, int topK, String label) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        log.info("[RAG {}] {} {} — similaritySearch(topK={}, query={})", flowId, RagFlowLogStep.RETRIEVE, label, topK, query);
        long retrieveStart = System.nanoTime();
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        long retrieveMs = (System.nanoTime() - retrieveStart) / 1_000_000;
        log.info("[RAG {}] {} 检索完成 — 返回 {} 个片段 ({} ms)", flowId, RagFlowLogStep.RETRIEVE, documents.size(), retrieveMs);
        return documents;
    }

    private boolean shouldFanOutAdmissions(String message) {
        RagProperties.Admissions admissions = ragProperties.admissions();
        if (!admissions.enabled()) {
            return false;
        }
        if (message == null || message.isBlank()) {
            return false;
        }
        if (admissions.schools().isEmpty()) {
            return false;
        }
        if (!containsAny(message, admissions.intentKeywords())) {
            return false;
        }
        // If the user already mentioned a specific school alias, don't fan out.
        for (RagProperties.School school : admissions.schools()) {
            if (containsAny(message, school.aliases())) {
                return false;
            }
            if (school.displayName() != null && !school.displayName().isBlank()
                    && message.toLowerCase(Locale.ROOT).contains(school.displayName().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private List<Document> fanOutAdmissionsSearch(String flowId, String message) {
        RagProperties.Admissions admissions = ragProperties.admissions();
        int perSchoolTopK = Math.max(1, admissions.perSchoolTopK());
        int totalTopK = Math.max(1, admissions.totalTopK());

        log.info("[RAG {}] {} 招生问题未指明学校 — 多学校检索: schools={}, perSchoolTopK={}, totalTopK={}, query={}",
                flowId, RagFlowLogStep.RETRIEVE,
                admissions.schools().size(), perSchoolTopK, totalTopK, message);

        long retrieveStart = System.nanoTime();
        List<Document> merged = new ArrayList<>();
        for (RagProperties.School school : admissions.schools()) {
            String schoolQuery = buildSchoolQuery(school, message);
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(schoolQuery)
                    .topK(perSchoolTopK)
                    .build());
            for (Document doc : docs) {
                if (doc.getMetadata() != null) {
                    doc.getMetadata().putIfAbsent("school", school.key());
                }
            }
            log.info("[RAG {}] {}   school={} query={} -> {} chunk(s)",
                    flowId, RagFlowLogStep.RETRIEVE, school.key(), schoolQuery, docs.size());
            merged.addAll(docs);
        }

        List<Document> deduped = dedupeAndSort(merged);
        int dedupedCount = deduped.size();
        if (deduped.size() > totalTopK) {
            deduped = deduped.subList(0, totalTopK);
        }
        long retrieveMs = (System.nanoTime() - retrieveStart) / 1_000_000;
        log.info("[RAG {}] {} 多学校检索完成 — merged={} deduped={} used={} ({} ms)",
                flowId, RagFlowLogStep.RETRIEVE, merged.size(), dedupedCount, deduped.size(), retrieveMs);
        return deduped;
    }

    private static String buildSchoolQuery(RagProperties.School school, String message) {
        String prefix = null;
        if (school.displayName() != null && !school.displayName().isBlank()) {
            prefix = school.displayName().strip();
        }
        else if (!school.aliases().isEmpty()) {
            prefix = school.aliases().get(0).strip();
        }
        if (prefix == null || prefix.isBlank()) {
            prefix = school.key();
        }
        return prefix + " " + message;
    }

    private static boolean containsAny(String message, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<Document> dedupeAndSort(List<Document> documents) {
        // Preserve best score per (school, source, text)
        Map<String, Document> best = new LinkedHashMap<>();
        for (Document doc : documents) {
            String school = String.valueOf(doc.getMetadata() == null ? "" : doc.getMetadata().getOrDefault("school", ""));
            String source = String.valueOf(doc.getMetadata() == null ? "" : doc.getMetadata().getOrDefault("source", ""));
            String text = doc.getText() == null ? "" : doc.getText();
            String key = school + "||" + source + "||" + Objects.hash(text);
            Document existing = best.get(key);
            if (existing == null) {
                best.put(key, doc);
                continue;
            }
            if (distanceOf(doc) < distanceOf(existing)) {
                best.put(key, doc);
            }
        }
        List<Document> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(RagRetrievalService::distanceOf));
        return out;
    }

    private static double distanceOf(Document document) {
        if (document == null || document.getMetadata() == null) {
            return 1.0;
        }
        Object distance = document.getMetadata().get("distance");
        if (distance instanceof Number number) {
            return number.doubleValue();
        }
        Object score = document.getMetadata().get("score");
        if (score instanceof Number number) {
            // Higher score means closer; invert to distance-like ordering.
            return 1.0 - number.doubleValue();
        }
        Object similarity = document.getMetadata().get("similarity");
        if (similarity instanceof Number number) {
            return 1.0 - number.doubleValue();
        }
        return 1.0;
    }

    public void logRetrievedDocuments(String flowId, List<Document> sources) {
        if (sources.isEmpty()) {
            log.warn("[RAG {}] {} 无可用文档片段", flowId, RagFlowLogStep.RETRIEVE);
            return;
        }

        log.info("[RAG {}] {} 采用 {} 个片段注入上下文", flowId, RagFlowLogStep.RETRIEVE, sources.size());
        for (int i = 0; i < sources.size(); i++) {
            Document document = sources.get(i);
            String title = String.valueOf(document.getMetadata().getOrDefault("title", "unknown"));
            String source = String.valueOf(document.getMetadata().getOrDefault("source", title));
            String school = String.valueOf(document.getMetadata().getOrDefault("school", ""));
            String score = formatSimilarityMetadata(document.getMetadata());
            log.info("[RAG {}]   chunk[{}] school={} source={} title={} {}\n{}",
                    flowId,
                    i + 1,
                    school,
                    source,
                    title,
                    score,
                    RagFlowLoggingAdvisor.truncate(document.getText()));
        }
    }

    private static String formatSimilarityMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object distance = metadata.get("distance");
        if (distance != null) {
            return "distance=" + distance;
        }
        Object score = metadata.get("score");
        if (score != null) {
            return "score=" + score;
        }
        Object similarity = metadata.get("similarity");
        if (similarity != null) {
            return "similarity=" + similarity;
        }
        return "metadata=" + metadata;
    }
}
