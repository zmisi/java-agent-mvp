package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

@FunctionalInterface
public interface ConversationSummaryGenerator {

    String generate(String conversationId, List<Message> history);
}
