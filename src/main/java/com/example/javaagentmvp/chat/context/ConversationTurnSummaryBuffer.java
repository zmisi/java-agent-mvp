package com.example.javaagentmvp.chat.context;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConversationTurnSummaryBuffer {

    private static final int MAX_PER_CONVERSATION = 120;
    private static final int GOAL_MAX_CHARS = 100;
    private static final int FINDING_MAX_CHARS = 100;

    private final Map<String, Deque<String>> byConversation = new ConcurrentHashMap<>();

    public void appendTurn(String conversationId, String userMessage, String assistantReply) {
        if (conversationId == null || conversationId.isBlank()) return;
        String goal = trimOneLine(userMessage, GOAL_MAX_CHARS);
        String finding = extractSeveralSentences(assistantReply, 3, FINDING_MAX_CHARS);
        if (goal.isBlank() && finding.isBlank()) return;
        String row = "goal=" + goal + " ; finding=" + finding;
        Deque<String> deque = byConversation.computeIfAbsent(conversationId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(row);
            while (deque.size() > MAX_PER_CONVERSATION) deque.removeFirst();
        }
    }

    public List<String> recent(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0) return List.of();
        Deque<String> deque = byConversation.get(conversationId);
        if (deque == null || deque.isEmpty()) return List.of();
        List<String> all;
        synchronized (deque) { all = new ArrayList<>(deque); }
        int start = Math.max(0, all.size() - limit);
        return all.subList(start, all.size());
    }

    private static String extractSeveralSentences(String text, int maxSentences, int maxChars) {
        String oneLine = trimOneLine(text, maxChars * 2);
        if (oneLine.isEmpty()) return "";
        Pattern sentenceEnd = Pattern.compile("[。.!?！？]");
        Matcher m = sentenceEnd.matcher(oneLine);
        int endPos = -1;
        int count = 0;
        while (m.find()) {
            endPos = m.end();
            count++;
            if (count >= maxSentences) break;
        }
        String result = (endPos > 0) ? oneLine.substring(0, endPos) : oneLine;
        if (result.length() > maxChars) result = result.substring(0, maxChars) + "…";
        return result.strip();
    }

    private static String trimOneLine(String text, int maxChars) {
        if (text == null) return "";
        String oneLine = CompactionMessageUtils.oneLine(text);
        if (oneLine.length() <= maxChars) return oneLine;
        return oneLine.substring(0, maxChars) + "…";
    }
}
