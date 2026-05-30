package com.example.javaagentmvp.chat.context;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConversationTurnSummaryBuffer {

    private static final int MAX_PER_CONVERSATION = 120;
    private static final int GOAL_MAX_CHARS = 100;
    private static final int FINDING_MAX_CHARS = 100;

    private final Map<String, Deque<String>> byConversation = new ConcurrentHashMap<>();

    public void appendTurn(String conversationId, String userMessage, String assistantReply) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String goal = trimOneLine(userMessage, GOAL_MAX_CHARS);
        String finding = trimOneLine(extractFirstSentence(assistantReply), FINDING_MAX_CHARS);
        if (goal.isBlank() && finding.isBlank()) {
            return;
        }
        String row = "goal=" + goal + " ; finding=" + finding;
        Deque<String> deque = byConversation.computeIfAbsent(conversationId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(row);
            while (deque.size() > MAX_PER_CONVERSATION) {
                deque.removeFirst();
            }
        }
    }

    public List<String> recent(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0) {
            return List.of();
        }
        Deque<String> deque = byConversation.get(conversationId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        List<String> all;
        synchronized (deque) {
            all = new ArrayList<>(deque);
        }
        int start = Math.max(0, all.size() - limit);
        return all.subList(start, all.size());
    }

    private static String extractFirstSentence(String text) {
        String oneLine = trimOneLine(text, FINDING_MAX_CHARS * 2);
        int idx = oneLine.indexOf('。');
        if (idx > 0) {
            return oneLine.substring(0, idx + 1);
        }
        idx = oneLine.indexOf('.');
        if (idx > 0) {
            return oneLine.substring(0, idx + 1);
        }
        return oneLine;
    }

    private static String trimOneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String oneLine = CompactionMessageUtils.oneLine(text);
        if (oneLine.length() <= maxChars) {
            return oneLine;
        }
        return oneLine.substring(0, maxChars) + "…";
    }
}
