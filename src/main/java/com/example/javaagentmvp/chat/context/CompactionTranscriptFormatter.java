package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

final class CompactionTranscriptFormatter {

    private CompactionTranscriptFormatter() {
    }

    static String format(List<Message> history, int messageLimit, int maxCharsPerMessage) {
        if (history.isEmpty()) {
            return "";
        }
        int start = Math.max(0, history.size() - messageLimit);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            Message message = history.get(i);
            MessageType type = message.getMessageType();
            if (type == MessageType.SYSTEM) {
                continue;
            }
            String text = CompactionMessageUtils.safeText(message).strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.length() > maxCharsPerMessage) {
                text = text.substring(0, maxCharsPerMessage) + "…";
            }
            sb.append('[').append(type.name()).append("] ").append(text).append('\n');
        }
        return sb.toString().strip();
    }
}
