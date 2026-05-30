package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;

final class CompactionMessageUtils {

    private static final String ELLIPSIS = " … ";

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

    static String abbreviateMiddle(String text, int maxChars) {
        String normalized = oneLine(text);
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        if (maxChars <= ELLIPSIS.length() + 8) {
            return normalized.substring(0, Math.max(1, maxChars - 1)).strip() + "…";
        }
        int remaining = maxChars - ELLIPSIS.length();
        int head = (int) Math.ceil(remaining * 0.6);
        int tail = remaining - head;
        if (head < 4) {
            head = 4;
            tail = remaining - head;
        }
        if (tail < 4) {
            tail = 4;
            head = remaining - tail;
        }
        String prefix = normalized.substring(0, Math.min(head, normalized.length())).strip();
        String suffix = normalized.substring(Math.max(0, normalized.length() - tail)).strip();
        if (prefix.isEmpty() || suffix.isEmpty()) {
            return normalized.substring(0, Math.max(1, maxChars - 1)).strip() + "…";
        }
        return prefix + ELLIPSIS + suffix;
    }
}
