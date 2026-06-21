package com.example.javaagentmvp.chat;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;

/** Extracts current and prior user turns from a chat request for routing and turn resolution. */
public final class UserTurnContextExtractor {

    private UserTurnContextExtractor() {
    }

    public record UserTurnContext(
            String currentUserMessage,
            List<String> priorUserMessages,
            List<String> priorContextHints) {
    }

    public static UserTurnContext extract(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        List<String> userMessages = collectUserMessagesNewestFirst(messages);
        if (userMessages.isEmpty()) {
            List<String> assistantHints = collectAssistantHintsNewestFirst(messages);
            return new UserTurnContext("", List.of(), List.copyOf(assistantHints));
        }
        String current = userMessages.get(0);
        List<String> prior = userMessages.size() == 1 ? List.of() : userMessages.subList(1, userMessages.size());
        List<String> hints = new ArrayList<>(prior);
        hints.addAll(collectAssistantHintsNewestFirst(messages));
        return new UserTurnContext(current, List.copyOf(prior), List.copyOf(hints));
    }

    /**
     * Prior user turns from chat memory, newest first, excluding {@code currentUserMessage} when it
     * matches the latest stored user turn.
     */
    public static List<String> priorUserMessagesFromHistory(List<Message> messages, String currentUserMessage) {
        List<String> userMessages = collectUserMessagesNewestFirst(messages);
        if (userMessages.isEmpty()) {
            return List.of();
        }
        String normalizedCurrent = currentUserMessage == null ? "" : currentUserMessage.strip();
        if (!normalizedCurrent.isEmpty() && normalizedCurrent.equals(userMessages.get(0))) {
            return userMessages.size() == 1 ? List.of() : List.copyOf(userMessages.subList(1, userMessages.size()));
        }
        return List.copyOf(userMessages);
    }

    private static List<String> collectUserMessagesNewestFirst(List<Message> messages) {
        List<String> userMessages = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return userMessages;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.USER) {
                String cleaned = UserMessageTextCleaner.clean(message.getText());
                if (cleaned != null && !cleaned.isBlank()) {
                    userMessages.add(cleaned.strip());
                }
            }
        }
        return userMessages;
    }

    private static List<String> collectAssistantHintsNewestFirst(List<Message> messages) {
        List<String> assistantHints = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return assistantHints;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.getMessageType() == MessageType.ASSISTANT) {
                String hint = compactHint(message.getText());
                if (!hint.isBlank()) {
                    assistantHints.add(hint);
                }
            }
        }
        return assistantHints;
    }

    private static String compactHint(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").strip();
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160);
    }
}
