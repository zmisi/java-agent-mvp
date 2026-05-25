package com.example.javaagentmvp.dbagent;

import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

@Repository
public class DesignDocumentService {

    private final Path designDocsRoot;

    public DesignDocumentService(ResolvedDbAgentPaths paths) {
        this.designDocsRoot = paths.designDocsRoot();
    }

    public List<DesignDocSummary> listDocuments() {
        if (!designDocsRoot.toFile().exists()) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(designDocsRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .map(this::toSummary)
                    .sorted(Comparator.comparing(DesignDocSummary::relativePath))
                    .toList();
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to list design documents under " + designDocsRoot, ex);
        }
    }

    public DesignDocContent readDocument(String relativePath) {
        Path resolved = resolveRelativePath(relativePath);
        if (!Files.isRegularFile(resolved)) {
            throw new DesignDocumentNotFoundException(relativePath);
        }
        try {
            String content = Files.readString(resolved);
            return new DesignDocContent(relativePath, content);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to read design document: " + relativePath, ex);
        }
    }

    public DesignDocSummary uploadDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("file name is required");
        }
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("only .md design documents are supported");
        }

        String safeName = sanitizeFileName(originalName);
        Path uploadDir = designDocsRoot.resolve("uploads");
        try {
            Files.createDirectories(uploadDir);
            String uniqueName = uniqueFileName(uploadDir, safeName);
            Path target = uploadDir.resolve(uniqueName);
            file.transferTo(target.toFile());
            String relative = "uploads/" + uniqueName;
            return new DesignDocSummary(relative, uniqueName);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to store design document upload", ex);
        }
    }

    private static String sanitizeFileName(String originalName) {
        String name = Path.of(originalName).getFileName().toString().strip();
        if (name.isBlank()) {
            throw new IllegalArgumentException("invalid file name");
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".md";
        stem = stem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
        if (stem.isBlank()) {
            stem = "design-doc";
        }
        if (stem.length() > 80) {
            stem = stem.substring(0, 80);
        }
        return stem + ext.toLowerCase(Locale.ROOT);
    }

    private static String uniqueFileName(Path directory, String fileName) throws IOException {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return fileName;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : ".md";
        for (int i = 1; i < 1000; i++) {
            String next = stem + "-" + i + ext;
            if (!Files.exists(directory.resolve(next))) {
                return next;
            }
        }
        throw new IllegalStateException("could not allocate unique file name for upload");
    }

    private DesignDocSummary toSummary(Path path) {
        String relative = designDocsRoot.relativize(path).toString().replace('\\', '/');
        return new DesignDocSummary(relative, path.getFileName().toString());
    }

    private Path resolveRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is required");
        }
        String normalized = relativePath.strip().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("..")) {
            throw new IllegalArgumentException("invalid design document path");
        }
        Path resolved = designDocsRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(designDocsRoot.normalize())) {
            throw new IllegalArgumentException("invalid design document path");
        }
        return resolved;
    }

    public record DesignDocSummary(String relativePath, String fileName) {
    }

    public record DesignDocContent(String relativePath, String content) {
    }
}
