package com.example.javaagentmvp.chat.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Declared model input budget for {@linkplain ContextUsageResponse#getUsedPercent() usage %}.
 * Tune to match your DashScope / OpenAI model context window.
 */
@ConfigurationProperties(prefix = "app.chat.context-window")
public record ChatContextWindowProperties(int maxInputTokens) {

    public ChatContextWindowProperties {
        if (maxInputTokens < 4096) {
            maxInputTokens = 131_072;
        }
    }
}
