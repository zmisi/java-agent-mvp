package com.example.javaagentmvp.chat.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatContextUsageComputerTest {

    @Test
    void lastUserMessageGoesToCurrentUserCategory() {
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("You are helpful."),
                new UserMessage("first question"),
                new AssistantMessage("first answer"),
                new UserMessage("second question")));

        ContextUsageResponse r = ChatContextUsageComputer.compute(prompt, 100_000);

        assertThat(r.totalEstimatedInputTokens()).isPositive();
        assertThat(r.contextWindowTokens()).isEqualTo(100_000);
        assertThat(r.breakdown()).anyMatch(row -> "current_user".equals(row.category()) && row.estimatedTokens() > 0);
        assertThat(r.breakdown()).anyMatch(row -> "memory_user".equals(row.category()) && row.estimatedTokens() > 0);
        assertThat(r.breakdown()).anyMatch(row -> "memory_assistant".equals(row.category()));
    }
}
