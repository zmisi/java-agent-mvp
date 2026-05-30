package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.List;

final class CompactionSummaryValidator {

    private static final String NO_SUMMARY = "(no summary available)";

    private CompactionSummaryValidator() {
    }

    static boolean isValid(String summary, List<Message> history) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        String normalized = summary.strip();
        if (history.isEmpty()) {
            return NO_SUMMARY.equals(normalized);
        }
        if (NO_SUMMARY.equals(normalized)) {
            return false;
        }
        if (!normalized.startsWith("Compact brief:")) {
            return false;
        }
        if (!normalized.contains("- User goals:")) {
            return false;
        }
        if (!normalized.contains("- Next step:")) {
            return false;
        }
        if (!normalized.contains(CompactionBriefParts.CANONICAL_NEXT_STEP)) {
            return false;
        }
        if (hasAssistantOrToolReply(history) && !normalized.contains("- Known findings:")) {
            return false;
        }
        if (countUserMessages(history) > 1 && !hasEnoughUserGoals(normalized, history)) {
            return false;
        }
        return allFindingsAcceptable(normalized);
    }

    private static boolean allFindingsAcceptable(String summary) {
        for (String finding : CompactionBriefParser.parse(summary).knownFindings()) {
            if (!CompactionFindingSanitizer.isAcceptable(CompactionFindingSanitizer.sanitize(finding))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasEnoughUserGoals(String summary, List<Message> history) {
        int expected = Math.min(countUserMessages(history), 6);
        if (expected <= 1) {
            return true;
        }
        int actual = CompactionBriefParser.parse(summary).userGoals().size();
        return actual >= expected;
    }

    private static int countUserMessages(List<Message> history) {
        int count = 0;
        for (Message message : history) {
            if (message.getMessageType() == MessageType.USER
                    && !CompactionMessageUtils.safeText(message).strip().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean hasAssistantOrToolReply(List<Message> history) {
        for (Message message : history) {
            MessageType type = message.getMessageType();
            if (type == MessageType.ASSISTANT || type == MessageType.TOOL) {
                String text = CompactionMessageUtils.safeText(message).strip();
                if (!text.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }
}
