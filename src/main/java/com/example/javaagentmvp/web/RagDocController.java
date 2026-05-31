package com.example.javaagentmvp.web;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

@RestController
@RequestMapping("/api/rag-docs")
public class RagDocController {

    private final ResourceLoader resourceLoader;

    public RagDocController(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @GetMapping("/open")
    public ResponseEntity<String> open(@RequestParam("source") String source) {
        if (source == null || source.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source is required");
        }
        String trimmed = source.strip();
        if (isHttpUrl(trimmed)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(trimmed))
                    .build();
        }
        String classpathPath = normalizeToRagDocsPath(trimmed);
        Resource resource = resourceLoader.getResource("classpath:" + classpathPath);
        if (!resource.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RAG source not found: " + source);
        }
        try {
            String body = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                    .body(body);
        }
        catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to open RAG source", ex);
        }
    }

    private static boolean isHttpUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String normalizeToRagDocsPath(String source) {
        String normalized = source.replace('\\', '/');
        int idx = normalized.toLowerCase(Locale.ROOT).indexOf("rag-docs/");
        if (idx < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source must point to rag-docs");
        }
        String subPath = normalized.substring(idx);
        Path candidate = Paths.get(subPath).normalize();
        String clean = candidate.toString().replace('\\', '/');
        if (clean.isBlank() || !clean.startsWith("rag-docs/") || clean.contains("../")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid source path");
        }
        return clean;
    }
}
