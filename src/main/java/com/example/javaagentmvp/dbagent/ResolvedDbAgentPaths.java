package com.example.javaagentmvp.dbagent;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ResolvedDbAgentPaths {

    private final Path designDocsRoot;
    private final Path releasesRoot;

    public ResolvedDbAgentPaths(DbAgentProperties properties, ResourceLoader resourceLoader) {
        this.designDocsRoot = resolveDirectory(properties.designDocsPath(), resourceLoader, "design-docs-path");
        this.releasesRoot = resolveDirectory(properties.releasesPath(), resourceLoader, "releases-path");
    }

    public Path designDocsRoot() {
        return designDocsRoot;
    }

    public Path releasesRoot() {
        return releasesRoot;
    }

    private static Path resolveDirectory(String location, ResourceLoader resourceLoader, String label) {
        try {
            var resource = resourceLoader.getResource(location);
            Path path = resource.getFile().toPath().toAbsolutePath().normalize();
            java.nio.file.Files.createDirectories(path);
            return path;
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to resolve " + label + ": " + location, ex);
        }
    }
}
