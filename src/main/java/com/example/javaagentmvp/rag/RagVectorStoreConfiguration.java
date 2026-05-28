package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
@ConditionalOnProperty(prefix = "app.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagVectorStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RagVectorStoreConfiguration.class);

    @Bean
    VectorStore ragVectorStore(
            EmbeddingModel embeddingModel,
            ResourcePatternResolver resourcePatternResolver,
            RagProperties ragProperties) throws IOException {
        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        List<Document> documents = loadDocuments(resourcePatternResolver, ragProperties);
        for (Document document : documents) {
            String source = String.valueOf(document.getMetadata().getOrDefault("source", "unknown"));
            int chars = document.getText() == null ? 0 : document.getText().length();
            log.info("RAG index: loaded document source={} chars={}", source, chars);
        }
        List<Document> chunks = new TokenTextSplitter().apply(documents);
        vectorStore.add(chunks);
        log.info("RAG index: {} documents -> {} chunks in SimpleVectorStore (pattern={})",
                documents.size(), chunks.size(), ragProperties.documentLocationPattern());
        return vectorStore;
    }

    private static List<Document> loadDocuments(
            ResourcePatternResolver resourcePatternResolver,
            RagProperties ragProperties) throws IOException {
        String documentLocationPattern = ragProperties.documentLocationPattern();
        Resource[] resources = resourcePatternResolver.getResources(documentLocationPattern);
        if (resources.length == 0) {
            throw new IllegalStateException("No RAG documents found at " + documentLocationPattern);
        }

        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename() == null ? resource.getDescription() : resource.getFilename();
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            String source = resolveSourcePath(resource).orElse(filename);
            String school = resolveSchoolKey(source, ragProperties);
            documents.add(new Document(content, Map.of(
                    "source", source,
                    "title", filename,
                    "school", school)));
        }
        return documents;
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
}
