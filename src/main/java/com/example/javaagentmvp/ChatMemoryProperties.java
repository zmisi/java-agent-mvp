package com.example.javaagentmvp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat.memory")
public record ChatMemoryProperties(int maxMessages) {

    public ChatMemoryProperties {
        if (maxMessages < 2) {
            maxMessages = 20;
        }
    }
}
