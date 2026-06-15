package com.example.javaagentmvp.rag;

import com.example.javaagentmvp.observability.AgentMetrics;
import com.example.javaagentmvp.observability.TraceResponseFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);
    private static final String SQL_IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String LEXICAL_ENGINE_AUTO = "auto";
    private static final String LEXICAL_ENGINE_PGROONGA = "pgroonga";
    private static final String LEXICAL_ENGINE_POSTGRES_FTS = "postgres-fts";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9]{2,}");
    private static final Set<String> FOLLOW_UP_STOPWORDS = Set.of(
            "介绍", "一下", "请问", "想了解", "关于", "仅", "只看", "这个", "那个", "学校");

    private final VectorStore vectorStore;

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final RagProperties ragProperties;

    private final AgentMetrics agentMetrics;

    private final io.micrometer.observation.ObservationRegistry observationRegistry;

    public RagRetrievalService(
            VectorStore ragVectorStore,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            RagProperties ragProperties,
            AgentMetrics agentMetrics,
            io.micrometer.observation.ObservationRegistry observationRegistry) {
        this.vectorStore = ragVectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.ragProperties = ragProperties;
        this.agentMetrics = agentMetrics;
        this.observationRegistry = observationRegistry;
    }

    public List<Document> search(String message, List<String> priorUserMessages, List<String> priorContextHints) {
        long startedAt = System.nanoTime();
        String mode = ragProperties.hybrid().enabled() ? "hybrid" : "vector-only";
        List<Document> documents = TraceResponseFilter.observe(
                observationRegistry,
                "agent.rag.retrieve",
                mode,
                () -> searchInternal(message, priorUserMessages, priorContextHints));
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        agentMetrics.recordRagRetrieve(mode, elapsedMs, documents.size());
        return documents;
    }

    private List<Document> searchInternal(
            String message, List<String> priorUserMessages, List<String> priorContextHints) {
        String flowId = RagFlowContext.flowId();
        String normalized = message == null ? "" : message.strip();
        List<String> priorTurns = priorUserMessages == null ? List.of() : priorUserMessages;
        List<String> contextHints = priorContextHints == null ? List.of() : priorContextHints;
        RewriteDecision rewriteDecision = buildContextAwareAdmissionsQuery(normalized, priorTurns, contextHints);
        String retrievalQuery = rewriteDecision.query();
        boolean rewritten = !retrievalQuery.equals(normalized);
        log.info("[RAG {}] {} query rewrite — rewritten={} hintSource={} original={} rewrittenQuery={}",
                flowId, RagFlowLogStep.RETRIEVE, rewritten, rewriteDecision.hintSource(), normalized, retrievalQuery);
        boolean admissionsIntent = isAdmissionsIntent(normalized, priorTurns, contextHints);
        boolean mentionsSpecificSchool = mentionsSpecificSchool(normalized);

        if (shouldFanOutAdmissions(normalized, priorTurns, contextHints)) {
            List<Document> documents = fanOutAdmissionsSearch(flowId, retrievalQuery);
            RagFlowContext.beginMultiSchoolAdmissions(documents);
            return documents;
        }

        int candidateTopK = admissionsIntent && mentionsSpecificSchool
                ? Math.max(ragProperties.topK(), ragProperties.topK() * 3)
                : ragProperties.topK();
        List<Document> candidates = hybridSearch(flowId, retrievalQuery, candidateTopK, "检索相关资料");
        return prioritizeAdmissionsSources(candidates, admissionsIntent, ragProperties.topK());
    }

    private List<Document> hybridSearch(String flowId, String query, int topK, String label) {
        RagProperties.Hybrid hybrid = ragProperties.hybrid();
        int vectorTopK = Math.max(topK, topK * hybrid.vectorTopKMultiplier());
        int lexicalTopK = Math.max(topK, topK * hybrid.lexicalTopKMultiplier());

        if (!hybrid.enabled()) {
            log.info("[RAG {}] {} {} — vector-only retrieve(topK={}, query={})",
                    flowId, RagFlowLogStep.RETRIEVE, label, vectorTopK, query);
            long retrieveStart = System.nanoTime();
            List<Document> vectorOnly = vectorSearch(flowId, query, vectorTopK);
            List<Document> deduped = dedupeAndSort(vectorOnly);
            if (deduped.size() > topK) {
                deduped = new ArrayList<>(deduped.subList(0, topK));
            }
            long retrieveMs = (System.nanoTime() - retrieveStart) / 1_000_000;
            log.info("[RAG {}] {} 检索完成 — vector={} used={} ({} ms)",
                    flowId, RagFlowLogStep.RETRIEVE, vectorOnly.size(), deduped.size(), retrieveMs);
            return deduped;
        }

        log.info("[RAG {}] {} {} — hybrid retrieve(vectorTopK={}, lexicalTopK={}, query={})",
                flowId, RagFlowLogStep.RETRIEVE, label, vectorTopK, lexicalTopK, query);
        long retrieveStart = System.nanoTime();

        CompletableFuture<List<Document>> vectorFuture = CompletableFuture.supplyAsync(
                () -> vectorSearch(flowId, query, vectorTopK));
        CompletableFuture<List<Document>> lexicalFuture = CompletableFuture.supplyAsync(
                () -> lexicalSearch(flowId, query, lexicalTopK));

        List<Document> vectorDocs = vectorFuture.join();
        List<Document> lexicalDocs = lexicalFuture.join();
        List<Document> fused = fuseByRrf(vectorDocs, lexicalDocs, topK * hybrid.fusionTopKMultiplier());
        if (fused.size() > topK) {
            fused = new ArrayList<>(fused.subList(0, topK));
        }
        long retrieveMs = (System.nanoTime() - retrieveStart) / 1_000_000;
        log.info("[RAG {}] {} 检索完成 — vector={} lexical={} fused={} used={} ({} ms)",
                flowId, RagFlowLogStep.RETRIEVE,
                vectorDocs.size(), lexicalDocs.size(), fused.size(), fused.size(), retrieveMs);
        log.info("[RAG {}] {} source-type distribution — vector={} lexical={} used={}",
                flowId, RagFlowLogStep.RETRIEVE,
                sourceTypeDistribution(vectorDocs),
                sourceTypeDistribution(lexicalDocs),
                sourceTypeDistribution(fused));
        log.info("[RAG {}] {} top sources — {}",
                flowId, RagFlowLogStep.RETRIEVE, topSourcesSummary(fused, 6));
        return fused;
    }

    private boolean shouldFanOutAdmissions(String message, List<String> priorUserMessages, List<String> priorContextHints) {
        RagProperties.Admissions admissions = ragProperties.admissions();
        if (!admissions.enabled()) {
            return false;
        }
        String normalized = message == null ? "" : message.strip();
        if (normalized.isBlank()) {
            return false;
        }
        if (admissions.schools().isEmpty()) {
            return false;
        }
        if (!isAdmissionsIntent(normalized, priorUserMessages, priorContextHints)) {
            return false;
        }
        // If the user already mentioned a specific school alias, don't fan out.
        for (RagProperties.School school : admissions.schools()) {
            if (containsAny(normalized, school.aliases())) {
                return false;
            }
            if (school.displayName() != null && !school.displayName().isBlank()
                    && normalized.toLowerCase(Locale.ROOT).contains(school.displayName().toLowerCase(Locale.ROOT))) {
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
            List<Document> docs = hybridSearch(flowId, schoolQuery, perSchoolTopK, "学校分片并行检索");
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

    private RewriteDecision buildContextAwareAdmissionsQuery(
            String message,
            List<String> priorUserMessages,
            List<String> priorContextHints) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isBlank()) {
            return new RewriteDecision(normalized, "none");
        }
        if (!isLikelyFollowUp(normalized)) {
            return new RewriteDecision(normalized, "none");
        }
        SnippetCandidate followUpSnippet = deriveFollowUpSnippet(priorUserMessages, priorContextHints);
        if (followUpSnippet.text().isBlank()) {
            return new RewriteDecision(normalized, "none");
        }
        return new RewriteDecision(normalized + " " + followUpSnippet.text(), followUpSnippet.source());
    }

    boolean isAdmissionsIntent(String message, List<String> priorUserMessages, List<String> priorContextHints) {
        String normalized = message == null ? "" : message.strip();
        if (containsAny(normalized, ragProperties.admissions().intentKeywords())) {
            return true;
        }
        if (!isLikelyFollowUp(normalized)) {
            return false;
        }
        if (!deriveAdmissionsIntentSnippet(priorUserMessages).isBlank()) {
            return true;
        }
        return !deriveAdmissionsIntentSnippet(priorContextHints).isBlank();
    }

    private boolean mentionsSpecificSchool(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isBlank()) {
            return false;
        }
        for (RagProperties.School school : ragProperties.admissions().schools()) {
            if (containsAny(normalized, school.aliases())) {
                return true;
            }
            if (school.displayName() != null && !school.displayName().isBlank()
                    && normalized.toLowerCase(Locale.ROOT).contains(school.displayName().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLikelyFollowUp(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String normalized = message.strip();
        if (normalized.length() <= 20) {
            return true;
        }
        return normalized.startsWith("仅")
                || normalized.startsWith("只看")
                || normalized.startsWith("那")
                || normalized.startsWith("这个")
                || normalized.startsWith("该");
    }

    private String deriveAdmissionsIntentSnippet(List<String> priorUserMessages) {
        if (priorUserMessages == null || priorUserMessages.isEmpty()) {
            return "";
        }
        RagProperties.Admissions admissions = ragProperties.admissions();
        Set<String> keywords = new LinkedHashSet<>();
        for (String prior : priorUserMessages) {
            if (prior == null || prior.isBlank()) {
                continue;
            }
            for (String keyword : admissions.intentKeywords()) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (prior.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                    keywords.add(keyword);
                }
            }
            if (keywords.size() >= 3) {
                break;
            }
        }
        if (keywords.isEmpty()) {
            return "";
        }
        return String.join(" ", keywords);
    }

    private SnippetCandidate deriveFollowUpSnippet(List<String> priorUserMessages, List<String> priorContextHints) {
        String fromUser = deriveSnippetFromCandidates(priorUserMessages);
        if (!fromUser.isBlank()) {
            return new SnippetCandidate(fromUser, "user");
        }
        String fromAssistant = deriveSnippetFromCandidates(priorContextHints);
        if (!fromAssistant.isBlank()) {
            return new SnippetCandidate(fromAssistant, "assistant");
        }
        return new SnippetCandidate("", "none");
    }

    private String deriveSnippetFromCandidates(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        for (String prior : candidates) {
            if (prior == null || prior.isBlank()) {
                continue;
            }
            String normalized = prior.strip();
            if (normalized.length() <= 24) {
                return normalized;
            }
            LinkedHashSet<String> tokens = new LinkedHashSet<>();
            Matcher matcher = TOKEN_PATTERN.matcher(normalized);
            while (matcher.find()) {
                String token = matcher.group();
                if (token == null || token.isBlank()) {
                    continue;
                }
                if (FOLLOW_UP_STOPWORDS.contains(token)) {
                    continue;
                }
                tokens.add(token);
                if (tokens.size() >= 4) {
                    break;
                }
            }
            if (!tokens.isEmpty()) {
                return String.join(" ", tokens);
            }
        }
        return "";
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

    private List<Document> vectorSearch(String flowId, String query, int topK) {
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return vectorStore.similaritySearch(searchRequest);
    }

    private List<Document> lexicalSearch(String flowId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String engine = ragProperties.hybrid().lexicalEngine();
        try {
            if (LEXICAL_ENGINE_PGROONGA.equals(engine)) {
                return lexicalSearchWithPgroonga(flowId, query, topK);
            }
            if (LEXICAL_ENGINE_POSTGRES_FTS.equals(engine)) {
                return lexicalSearchWithPostgresFts(flowId, query, topK);
            }
            // auto: prefer pgroonga for Chinese, fallback to postgres fts.
            try {
                return lexicalSearchWithPgroonga(flowId, query, topK);
            }
            catch (Exception ex) {
                log.warn("[RAG {}] {} pgroonga unavailable in auto mode — fallback postgres-fts: {}",
                        flowId, RagFlowLogStep.RETRIEVE, ex.getMessage());
                return lexicalSearchWithPostgresFts(flowId, query, topK);
            }
        }
        catch (Exception ex) {
            log.warn("[RAG {}] {} lexical retrieval failed (engine={}) — fallback to vector only: {}",
                    flowId, RagFlowLogStep.RETRIEVE, engine, ex.getMessage());
            return List.of();
        }
    }

    private List<Document> lexicalSearchWithPgroonga(String flowId, String query, int topK) {
        String table = qualifiedVectorTable();
        String sql = """
                SELECT t.id::text AS id,
                       t.content,
                       t.metadata::text AS metadata_json,
                       pgroonga_score(t.tableoid, t.ctid) AS lexical_rank
                FROM %s t
                WHERE t.content &@~ ?
                ORDER BY lexical_rank DESC, t.id
                LIMIT ?
                """.formatted(table);
        List<Document> docs = queryLexical(sql, query, topK, LEXICAL_ENGINE_PGROONGA);
        log.info("[RAG {}] {} lexical engine=pgroonga hits={}",
                flowId, RagFlowLogStep.RETRIEVE, docs.size());
        return docs;
    }

    private List<Document> lexicalSearchWithPostgresFts(String flowId, String query, int topK) {
        String table = qualifiedVectorTable();
        String dictionary = quoteFtsDictionary(ragProperties.hybrid().ftsDictionary());
        String sql = """
                SELECT id::text AS id,
                       content,
                       metadata::text AS metadata_json,
                       ts_rank_cd(to_tsvector(%s, COALESCE(content, '')),
                                  websearch_to_tsquery(%s, ?)) AS lexical_rank
                FROM %s
                WHERE to_tsvector(%s, COALESCE(content, ''))
                      @@ websearch_to_tsquery(%s, ?)
                ORDER BY lexical_rank DESC, id
                LIMIT ?
                """.formatted(dictionary, dictionary, table, dictionary, dictionary);
        List<Document> docs = queryLexical(sql, query, topK, LEXICAL_ENGINE_POSTGRES_FTS);
        log.info("[RAG {}] {} lexical engine=postgres-fts hits={}",
                flowId, RagFlowLogStep.RETRIEVE, docs.size());
        return docs;
    }

    private List<Document> queryLexical(String sql, String query, int topK, String engine) {
        return jdbcTemplate.query(sql, ps -> {
            if (LEXICAL_ENGINE_PGROONGA.equals(engine)) {
                ps.setString(1, query);
                ps.setInt(2, topK);
                return;
            }
            ps.setString(1, query);
            ps.setString(2, query);
            ps.setInt(3, topK);
        }, (rs, rowNum) -> {
            String id = rs.getString("id");
            String text = rs.getString("content");
            String metadataJson = rs.getString("metadata_json");
            double lexicalRank = rs.getDouble("lexical_rank");
            Map<String, Object> metadata = parseMetadata(metadataJson);
            metadata.put("lexical_rank", lexicalRank);
            metadata.put("lexical_engine", engine);
            return Document.builder()
                    .id(id)
                    .text(text == null ? "" : text)
                    .metadata(metadata)
                    .build();
        });
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        }
        catch (Exception ex) {
            return new HashMap<>();
        }
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

    private List<Document> fuseByRrf(List<Document> vectorDocs, List<Document> lexicalDocs, int limit) {
        RagProperties.Hybrid hybrid = ragProperties.hybrid();
        Map<String, RankedDoc> ranked = new LinkedHashMap<>();
        applyRrf(ranked, vectorDocs, hybrid.vectorWeight(), hybrid.rrfK());
        applyRrf(ranked, lexicalDocs, hybrid.lexicalWeight(), hybrid.rrfK());
        List<RankedDoc> sorted = new ArrayList<>(ranked.values());
        sorted.sort(Comparator
                .comparingDouble(RankedDoc::fusedScore).reversed()
                .thenComparingDouble(rd -> distanceOf(rd.document())));
        if (sorted.size() > limit) {
            sorted = sorted.subList(0, limit);
        }
        List<Document> out = new ArrayList<>(sorted.size());
        for (RankedDoc rankedDoc : sorted) {
            out.add(withRrfScore(rankedDoc.document(), rankedDoc.fusedScore()));
        }
        return out;
    }

    private static void applyRrf(Map<String, RankedDoc> ranked, List<Document> docs, double weight, int rrfK) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            double score = weight / (rrfK + i + 1.0);
            String key = fusionKey(doc);
            RankedDoc existing = ranked.get(key);
            if (existing == null) {
                ranked.put(key, new RankedDoc(doc, score));
            }
            else {
                existing.addScore(score);
                if (distanceOf(doc) < distanceOf(existing.document())) {
                    existing.setDocument(doc);
                }
            }
        }
    }

    private static String fusionKey(Document doc) {
        String source = sourceOf(doc);
        String text = doc == null || doc.getText() == null ? "" : doc.getText();
        return source + "||" + Objects.hash(text);
    }

    private String qualifiedVectorTable() {
        String schema = requireSqlIdentifier(ragProperties.vectorSchemaName(), "app.rag.vector-schema-name");
        String table = requireSqlIdentifier(ragProperties.vectorTableName(), "app.rag.vector-table-name");
        return schema + "." + table;
    }

    private static String requireSqlIdentifier(String value, String propertyName) {
        if (value == null || !value.matches(SQL_IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(propertyName + " must match " + SQL_IDENTIFIER_PATTERN + ", actual=" + value);
        }
        return value;
    }

    private static Document withRrfScore(Document document, double fusedScore) {
        Map<String, Object> metadata = new HashMap<>();
        if (document != null && document.getMetadata() != null) {
            metadata.putAll(document.getMetadata());
        }
        metadata.put("rrf_score", fusedScore);
        return Document.builder()
                .id(document == null ? null : document.getId())
                .text(document == null || document.getText() == null ? "" : document.getText())
                .metadata(metadata)
                .build();
    }

    private static String quoteFtsDictionary(String dictionary) {
        String normalized = dictionary == null ? "" : dictionary.strip();
        if (!normalized.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("app.rag.hybrid.fts-dictionary must be a valid identifier, actual=" + dictionary);
        }
        return "'" + normalized + "'";
    }

    private static List<Document> prioritizeAdmissionsSources(
            List<Document> documents,
            boolean admissionsIntent,
            int limit) {
        if (!admissionsIntent || documents.isEmpty()) {
            if (documents.size() <= limit) {
                return documents;
            }
            return documents.subList(0, limit);
        }
        List<Document> sorted = new ArrayList<>(documents);
        sorted.sort(Comparator
                .comparingInt((Document doc) -> sourceTypePriority(sourceOf(doc), true)).reversed()
                .thenComparing(Comparator.comparingDouble(RagRetrievalService::rrfScoreOf).reversed())
                .thenComparingDouble(RagRetrievalService::distanceOf));

        List<Document> preferred = sorted.stream()
                .filter(doc -> sourceTypePriority(sourceOf(doc), true) >= 2)
                .toList();
        List<Document> fallback = sorted.stream()
                .filter(doc -> sourceTypePriority(sourceOf(doc), true) < 2)
                .toList();

        List<Document> picked = new ArrayList<>(Math.min(limit, sorted.size()));
        appendUntilLimit(picked, preferred, limit);
        appendUntilLimit(picked, fallback, limit);
        return picked;
    }

    private static void appendUntilLimit(List<Document> out, List<Document> incoming, int limit) {
        if (incoming == null || incoming.isEmpty() || out.size() >= limit) {
            return;
        }
        for (Document document : incoming) {
            if (out.size() >= limit) {
                return;
            }
            out.add(document);
        }
    }

    private static String sourceOf(Document document) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        Object source = document.getMetadata().get("source");
        return source == null ? "" : source.toString().toLowerCase(Locale.ROOT);
    }

    private static int sourceTypePriority(String source, boolean admissionsIntent) {
        if (admissionsIntent) {
            if (source.contains("/charters/")) {
                return 3;
            }
            if (source.contains("/plans/")) {
                return 2;
            }
            if (source.contains("/scores/")) {
                return 1;
            }
            return 0;
        }
        if (source.contains("/plans/") || source.contains("/charters/") || source.contains("/scores/")) {
            return 1;
        }
        return 0;
    }

    private static String sourceTypeDistribution(List<Document> documents) {
        int plans = 0;
        int scores = 0;
        int charters = 0;
        int others = 0;
        for (Document document : documents) {
            String source = sourceOf(document);
            if (source.contains("/plans/")) {
                plans++;
            }
            else if (source.contains("/scores/")) {
                scores++;
            }
            else if (source.contains("/charters/")) {
                charters++;
            }
            else {
                others++;
            }
        }
        return "plans=" + plans + ", scores=" + scores + ", charters=" + charters + ", other=" + others;
    }

    private static String topSourcesSummary(List<Document> documents, int max) {
        if (documents == null || documents.isEmpty() || max <= 0) {
            return "(none)";
        }
        List<String> sources = new ArrayList<>();
        for (Document document : documents) {
            String source = sourceOf(document);
            if (source.isBlank()) {
                source = "(unknown)";
            }
            sources.add(source);
            if (sources.size() >= max) {
                break;
            }
        }
        return String.join(" | ", sources);
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

    private static double rrfScoreOf(Document document) {
        if (document == null || document.getMetadata() == null) {
            return 0.0;
        }
        Object score = document.getMetadata().get("rrf_score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        return 0.0;
    }

    private static final class RankedDoc {
        private Document document;
        private double fusedScore;

        private RankedDoc(Document document, double fusedScore) {
            this.document = document;
            this.fusedScore = fusedScore;
        }

        private Document document() {
            return document;
        }

        private void setDocument(Document document) {
            this.document = document;
        }

        private double fusedScore() {
            return fusedScore;
        }

        private void addScore(double delta) {
            this.fusedScore += delta;
        }
    }

    private record SnippetCandidate(String text, String source) {
    }

    private record RewriteDecision(String query, String hintSource) {
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
