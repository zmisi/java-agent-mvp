package com.example.javaagentmvp.admissionworkflow;

import com.example.javaagentmvp.ModelCallLoggingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.admission-workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AdmissionWorkflowProperties.class)
public class AdmissionWorkflowConfiguration {

    @Bean
    ChatClient workflowChatClient(
            ChatClient.Builder chatClientBuilder,
            AdmissionWorkflowProperties properties) {
        ChatOptions options = ChatOptions.builder()
                .temperature(properties.synthesis().temperature())
                .build();
        return chatClientBuilder
                .defaultOptions(options)
                .defaultAdvisors(new ModelCallLoggingAdvisor("WORKFLOW"))
                .build();
    }
}
