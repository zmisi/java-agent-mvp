package com.example.javaagentmvp.dbagent;

import org.springframework.stereotype.Component;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DraftSqlGenerator {

    private static final Pattern SQL_BLOCK = Pattern.compile("```(?:sql)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);

    private final org.springframework.ai.chat.client.ChatClient draftSqlChatClient;
    private final String systemPrompt;

    public DraftSqlGenerator(
            org.springframework.ai.chat.client.ChatClient draftSqlChatClient,
            DbAgentProperties properties,
            DbAgentTargetRegistry targetRegistry,
            ResourceLoader resourceLoader) {
        this.draftSqlChatClient = draftSqlChatClient;
        this.systemPrompt = loadSystemPrompt(
                properties.draftSqlPromptLocation(),
                targetRegistry.deploySchema(),
                resourceLoader);
    }

    public String generate(String designDocTitle, String designDocContent) {
        String userPrompt = """
                Design document title: %s

                --- design document ---
                %s
                --- end ---

                Generate PostgreSQL DDL migration SQL following the rules.
                """.formatted(designDocTitle, designDocContent);

        String raw = draftSqlChatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        return extractSql(raw);
    }

    private static String loadSystemPrompt(String location, String schema, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Draft SQL prompt not found: " + location);
        }
        try {
            String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            return raw.strip().replace("{schema}", schema);
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to read draft SQL prompt: " + location, ex);
        }
    }

    static String extractSql(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Model returned empty SQL");
        }
        Matcher matcher = SQL_BLOCK.matcher(raw);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return raw.strip();
    }
}
