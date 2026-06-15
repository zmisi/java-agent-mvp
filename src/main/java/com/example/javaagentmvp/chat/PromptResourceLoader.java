package com.example.javaagentmvp.chat;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class PromptResourceLoader {

    private PromptResourceLoader() {
    }

    static String load(String location, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("System prompt not found: " + location);
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to read system prompt: " + location, ex);
        }
    }
}
