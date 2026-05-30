package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;

final class CompactionMessageUtils {

    private CompactionMessageUtils() {
    }

    static String safeText(Message message) {
        try {
            return message.getText() == null ? "" : message.getText();
        } catch (UnsupportedOperationException ex) {
            return message.toString();
        }
    }

    static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s{2,}", " ").strip();
    }
}
