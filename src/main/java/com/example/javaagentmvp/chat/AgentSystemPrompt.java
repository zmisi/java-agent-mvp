package com.example.javaagentmvp.chat;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the agent system prompt from an external file (see {@link AgentPromptProperties#location()}).
 */
@Component
public class AgentSystemPrompt {

    private final String text;

    public AgentSystemPrompt(AgentPromptProperties properties, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(properties.location());
        if (!resource.exists()) {
            throw new IllegalStateException("System prompt not found: " + properties.location());
        }
        try {
            String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            this.text = applyPlaceholders(raw.strip(), properties);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to read system prompt: " + properties.location(), ex);
        }
    }

    public String text() {
        return text;
    }

    private static String applyPlaceholders(String raw, AgentPromptProperties properties) {
        return raw.replace("{schema}", properties.schema());
    }
}
