package com.example.javaagentmvp.chat;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;

@Configuration
public class ChatModelSelectionConfiguration {

    @Bean
    @Primary
    ChatModel primaryChatModel(
            Map<String, ChatModel> chatModels,
            @Value("${spring.ai.model.chat:}") String provider) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase();
        String configuredBeanName = normalizedProvider.isBlank()
                ? ""
                : normalizedProvider + "ChatModel";

        if (!configuredBeanName.isBlank() && chatModels.containsKey(configuredBeanName)) {
            return chatModels.get(configuredBeanName);
        }

        if (chatModels.size() == 1) {
            return chatModels.values().iterator().next();
        }

        throw new IllegalStateException(
                "Unable to select primary ChatModel. Set spring.ai.model.chat to one of "
                        + chatModels.keySet());
    }
}
