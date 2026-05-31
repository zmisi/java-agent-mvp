package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagVectorStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagVectorStoreConfiguration.class);
    private static final String LOG_TAG = "[RAG-INDEX]";
    private static final String SQL_IDENTIFIER_PATTERN = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String INGEST_STATE_TABLE = "agent_ui.rag_ingest_state";

    @Bean
    VectorStore ragVectorStore(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties) {
        String schema = requireSqlIdentifier(ragProperties.vectorSchemaName(), "app.rag.vector-schema-name");
        String table = requireSqlIdentifier(ragProperties.vectorTableName(), "app.rag.vector-table-name");
        ensurePgVectorTypeAvailable(jdbcTemplate);
        log.info("{} pgvector extension detected, using PgVectorStore {}.{}", LOG_TAG, schema, table);
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .schemaName(schema)
                .vectorTableName(table)
                .idType(PgVectorStore.PgIdType.TEXT)
                .initializeSchema(true)
                .build();
    }

    @Bean
    ApplicationRunner ragIndexerOnStartup(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            ResourcePatternResolver resourcePatternResolver,
            RagProperties ragProperties,
            @Value("${spring.ai.model.embedding:}") String embeddingProvider,
            @Value("${spring.ai.ollama.embedding.options.model:}") String ollamaEmbeddingModel,
            @Value("${spring.ai.dashscope.embedding.options.model:}") String dashscopeEmbeddingModel) {
        return args -> {
            Map<String, IngestState> ingestStateBySource = loadIngestState(jdbcTemplate);
            String embeddingFingerprint = buildEmbeddingFingerprint(
                    embeddingProvider,
                    ollamaEmbeddingModel,
                    dashscopeEmbeddingModel);
            if (ragProperties.rebuildOnStartup()) {
                log.info("{} startup full rebuild enabled", LOG_TAG);
            }
            else {
                log.info("{} startup incremental sync enabled (new/changed documents only)", LOG_TAG);
            }
            log.info("{} embedding fingerprint={}", LOG_TAG, embeddingFingerprint);

            if (ragProperties.clearBeforeRebuild()) {
                if (!ragProperties.rebuildOnStartup()) {
                    log.warn("{} ignore clear-before-rebuild because rebuild-on-startup=false", LOG_TAG);
                }
                String schema = requireSqlIdentifier(ragProperties.vectorSchemaName(), "app.rag.vector-schema-name");
                String table = requireSqlIdentifier(ragProperties.vectorTableName(), "app.rag.vector-table-name");
                String fqtn = schema + "." + table;
                if (ragProperties.rebuildOnStartup()) {
                    if (vectorStore instanceof PgVectorStore) {
                        log.warn("{} clear-before-rebuild enabled, truncating {}", LOG_TAG, fqtn);
                        jdbcTemplate.execute("TRUNCATE TABLE " + fqtn);
                    }
                    else {
                        log.warn("{} clear-before-rebuild requested but current store is {}; skip SQL truncate",
                                LOG_TAG, vectorStore.getClass().getSimpleName());
                    }
                    jdbcTemplate.execute("TRUNCATE TABLE " + INGEST_STATE_TABLE);
                    ingestStateBySource.clear();
                }
            }

            List<SourceDocument> documents = loadDocuments(resourcePatternResolver, ragProperties, embeddingFingerprint);
            for (SourceDocument document : documents) {
                int chars = document.content() == null ? 0 : document.content().length();
                log.info("{} loaded document source={} chars={}", LOG_TAG, document.source(), chars);
            }
            SyncStats stats = syncDocuments(
                    vectorStore,
                    jdbcTemplate,
                    documents,
                    ingestStateBySource,
                    ragProperties,
                    ragProperties.rebuildOnStartup());
            log.info("{} summary: mode={} addedDocs={} updatedDocs={} reindexedDocs={} indexedDocs={} skippedDocs={} removedDocs={} addedChunks={} deletedChunks={} store={} pattern={}",
                    LOG_TAG,
                    ragProperties.rebuildOnStartup() ? "rebuild" : "incremental",
                    stats.addedDocuments,
                    stats.updatedDocuments,
                    stats.reindexedDocuments,
                    stats.indexedDocuments,
                    stats.skippedDocuments,
                    stats.removedDocuments,
                    stats.addedChunks,
                    stats.deletedChunks,
                    vectorStore.getClass().getSimpleName(),
                    ragProperties.documentLocationPattern());
        };
    }

    private static String requireSqlIdentifier(String value, String propertyName) {
        if (value == null || !value.matches(SQL_IDENTIFIER_PATTERN)) {
            throw new IllegalArgumentException(propertyName + " must match " + SQL_IDENTIFIER_PATTERN + ", actual=" + value);
        }
        return value;
    }

    private static void ensurePgVectorTypeAvailable(JdbcTemplate jdbcTemplate) {
        try {
            // Try to provision extension when DB permissions allow it.
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        }
        catch (Exception ex) {
            log.warn("{} unable to create extension 'vector' automatically: {}", LOG_TAG, ex.getMessage());
        }

        try {
            Boolean typeVisible = jdbcTemplate.queryForObject("SELECT to_regtype('vector') IS NOT NULL", Boolean.class);
            if (Boolean.TRUE.equals(typeVisible)) {
                return;
            }
        }
        catch (BadSqlGrammarException ex) {
            throw new IllegalStateException("Failed to verify pgvector type visibility", ex);
        }

        throw new IllegalStateException("""
                pgvector type 'vector' is unavailable in current database search path.
                Please ensure pgvector is installed and visible (usually schema public).
                Required SQL (run with privileged user):
                  CREATE EXTENSION IF NOT EXISTS vector;
                  SHOW search_path;
                  SELECT to_regtype('vector');
                """);
    }

    private static SyncStats syncDocuments(
            VectorStore vectorStore,
            JdbcTemplate jdbcTemplate,
            List<SourceDocument> sourceDocuments,
            Map<String, IngestState> ingestStateBySource,
            RagProperties ragProperties,
            boolean forceReindex) {
        int addedDocuments = 0;
        int updatedDocuments = 0;
        int reindexedDocuments = 0;
        int indexedDocuments = 0;
        int skippedDocuments = 0;
        int removedDocuments = 0;
        int addedChunks = 0;
        int deletedChunks = 0;
        Set<String> seenSources = new HashSet<>();

        for (SourceDocument sourceDocument : sourceDocuments) {
            seenSources.add(sourceDocument.source());
            IngestState previousState = ingestStateBySource.get(sourceDocument.source());
            boolean unchanged = previousState != null && sourceDocument.contentHash().equals(previousState.contentHash());
            if (!forceReindex && unchanged) {
                log.info("{} SKIP source={} reason=unchanged", LOG_TAG, sourceDocument.source());
                skippedDocuments++;
                continue;
            }

            List<Document> chunks = splitIntoChunks(sourceDocument);
            List<String> nextChunkIds = chunks.stream().map(Document::getId).toList();

            // Always clean before write in incremental/full sync:
            // - previous ids: remove old versions
            // - next ids: make write idempotent on retries
            Set<String> chunkIdsToDelete = new LinkedHashSet<>();
            if (previousState != null) {
                chunkIdsToDelete.addAll(previousState.chunkIds());
            }
            chunkIdsToDelete.addAll(nextChunkIds);
            if (!chunkIdsToDelete.isEmpty()) {
                deleteChunksById(jdbcTemplate, ragProperties, new ArrayList<>(chunkIdsToDelete));
                deletedChunks += chunkIdsToDelete.size();
            }

            vectorStore.add(chunks);
            addedChunks += chunks.size();
            indexedDocuments++;

            String action;
            if (previousState == null) {
                addedDocuments++;
                action = "ADD";
            }
            else if (unchanged && forceReindex) {
                reindexedDocuments++;
                action = "REINDEX";
            }
            else {
                updatedDocuments++;
                action = "UPDATE";
            }
            log.info("{} {} source={} deleteChunks={} addChunks={}",
                    LOG_TAG, action, sourceDocument.source(), chunkIdsToDelete.size(), chunks.size());

            upsertIngestState(jdbcTemplate, sourceDocument.source(), sourceDocument.contentHash(), nextChunkIds);
            ingestStateBySource.put(sourceDocument.source(), new IngestState(sourceDocument.contentHash(), nextChunkIds));
        }

        // Handle deleted documents for both incremental and full rebuild modes.
        for (Map.Entry<String, IngestState> entry : new ArrayList<>(ingestStateBySource.entrySet())) {
            String source = entry.getKey();
            if (seenSources.contains(source)) {
                continue;
            }
            List<String> chunkIds = entry.getValue().chunkIds();
            if (!chunkIds.isEmpty()) {
                deleteChunksById(jdbcTemplate, ragProperties, chunkIds);
                deletedChunks += chunkIds.size();
            }
            deleteIngestState(jdbcTemplate, source);
            ingestStateBySource.remove(source);
            removedDocuments++;
            log.info("{} DELETE source={} deleteChunks={}", LOG_TAG, source, chunkIds.size());
        }

        return new SyncStats(
                addedDocuments,
                updatedDocuments,
                reindexedDocuments,
                indexedDocuments,
                skippedDocuments,
                removedDocuments,
                addedChunks,
                deletedChunks);
    }

    private static List<Document> splitIntoChunks(SourceDocument sourceDocument) {
        Document document = new Document(
                sourceDocument.content(),
                Map.of(
                        "source", sourceDocument.source(),
                        "title", sourceDocument.title(),
                        "school", sourceDocument.school()));
        List<Document> rawChunks = new TokenTextSplitter().apply(List.of(document));
        List<Document> chunksWithIds = new ArrayList<>(rawChunks.size());
        for (int i = 0; i < rawChunks.size(); i++) {
            Document rawChunk = rawChunks.get(i);
            String chunkId = buildChunkId(sourceDocument.source(), sourceDocument.contentHash(), i);
            chunksWithIds.add(Document.builder()
                    .id(chunkId)
                    .text(rawChunk.getText())
                    .metadata(rawChunk.getMetadata())
                    .build());
        }
        return chunksWithIds;
    }

    private static String buildChunkId(String source, String contentHash, int chunkIndex) {
        String stableSeed = source + "|" + contentHash + "|" + chunkIndex;
        return UUID.nameUUIDFromBytes(stableSeed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Map<String, IngestState> loadIngestState(JdbcTemplate jdbcTemplate) {
        Map<String, IngestState> out = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT source, content_hash, chunk_ids FROM " + INGEST_STATE_TABLE, rs -> {
            String source = rs.getString("source");
            String contentHash = rs.getString("content_hash");
            String chunkIdsRaw = rs.getString("chunk_ids");
            List<String> chunkIds = decodeChunkIds(chunkIdsRaw);
            out.put(source, new IngestState(contentHash, chunkIds));
        });
        return out;
    }

    private static void upsertIngestState(
            JdbcTemplate jdbcTemplate,
            String source,
            String contentHash,
            List<String> chunkIds) {
        jdbcTemplate.update("""
                INSERT INTO agent_ui.rag_ingest_state (source, content_hash, chunk_ids, indexed_at, updated_at)
                VALUES (?, ?, ?, now(), now())
                ON CONFLICT (source)
                DO UPDATE SET content_hash = EXCLUDED.content_hash,
                              chunk_ids = EXCLUDED.chunk_ids,
                              updated_at = now()
                """, source, contentHash, encodeChunkIds(chunkIds));
    }

    private static void deleteIngestState(JdbcTemplate jdbcTemplate, String source) {
        jdbcTemplate.update("DELETE FROM " + INGEST_STATE_TABLE + " WHERE source = ?", source);
    }

    private static String encodeChunkIds(List<String> chunkIds) {
        return String.join("\n", chunkIds);
    }

    private static void deleteChunksById(
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String schema = requireSqlIdentifier(ragProperties.vectorSchemaName(), "app.rag.vector-schema-name");
        String table = requireSqlIdentifier(ragProperties.vectorTableName(), "app.rag.vector-table-name");
        String sql = "DELETE FROM " + schema + "." + table + " WHERE id::text = ?";
        jdbcTemplate.batchUpdate(sql, chunkIds, chunkIds.size(), (ps, id) -> ps.setString(1, id));
    }

    private static List<String> decodeChunkIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            if (line != null && !line.isBlank()) {
                out.add(line.strip());
            }
        }
        return out;
    }

    private static List<SourceDocument> loadDocuments(
            ResourcePatternResolver resourcePatternResolver,
            RagProperties ragProperties,
            String embeddingFingerprint) throws IOException {
        String documentLocationPattern = ragProperties.documentLocationPattern();
        Resource[] resources = resourcePatternResolver.getResources(documentLocationPattern);
        if (resources.length == 0) {
            throw new IllegalStateException("No RAG documents found at " + documentLocationPattern);
        }

        List<SourceDocument> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename() == null ? resource.getDescription() : resource.getFilename();
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            String source = resolveSourcePath(resource).orElse(filename);
            String school = resolveSchoolKey(source, ragProperties);
            String contentHash = sha256Hex(content + "\n#embedding-fingerprint:" + embeddingFingerprint);
            documents.add(new SourceDocument(source, filename, school, content, contentHash));
        }
        return documents;
    }

    private static String buildEmbeddingFingerprint(
            String embeddingProvider,
            String ollamaEmbeddingModel,
            String dashscopeEmbeddingModel) {
        String provider = embeddingProvider == null ? "" : embeddingProvider.trim().toLowerCase();
        return switch (provider) {
            case "ollama" -> "ollama:" + normalize(ollamaEmbeddingModel, "default");
            case "dashscope" -> "dashscope:" + normalize(dashscopeEmbeddingModel, "default");
            default -> "unknown:" + provider;
        };
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    private static Optional<String> resolveSourcePath(Resource resource) {
        try {
            String url = resource.getURL().toString();
            int idx = url.indexOf("/rag-docs/");
            if (idx >= 0) {
                return Optional.of(url.substring(idx + 1)); // drop leading '/'
            }
            return Optional.ofNullable(resource.getDescription());
        }
        catch (IOException ex) {
            return Optional.empty();
        }
    }

    private static String resolveSchoolKey(String source, RagProperties ragProperties) {
        if (source == null || source.isBlank()) {
            return "";
        }
        String lower = source.toLowerCase();
        for (RagProperties.School school : ragProperties.admissions().schools()) {
            for (String marker : school.pathContains()) {
                if (marker != null && !marker.isBlank() && lower.contains(marker.toLowerCase())) {
                    return school.key();
                }
            }
        }
        // best-effort: infer from common folder names if present
        if (lower.contains("/hfut/")) {
            return "hfut";
        }
        if (lower.contains("/hfuu/")) {
            return "hfuu";
        }
        if (lower.contains("/aust/")) {
            return "aust";
        }
        if (lower.contains("/ahau/")) {
            return "ahau";
        }
        return "";
    }

    private record SourceDocument(String source, String title, String school, String content, String contentHash) {
    }

    private record IngestState(String contentHash, List<String> chunkIds) {
    }

    private record SyncStats(
            int addedDocuments,
            int updatedDocuments,
            int reindexedDocuments,
            int indexedDocuments,
            int skippedDocuments,
            int removedDocuments,
            int addedChunks,
            int deletedChunks) {
    }
}
