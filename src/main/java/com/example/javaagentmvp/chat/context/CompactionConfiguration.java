package com.example.javaagentmvp.chat.context;

import com.example.javaagentmvp.ModelCallLoggingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CompactionProperties.class)
public class CompactionConfiguration {

    @Bean
    ChatClient compactionChatClient(
            ChatClient.Builder chatClientBuilder,
            CompactionProperties properties) {
        ChatOptions options = ChatOptions.builder().temperature(properties.temperature()).build();
        return chatClientBuilder
                .defaultOptions(options)
                .defaultAdvisors(new ModelCallLoggingAdvisor("COMPACTION"))
                .build();
    }
}
