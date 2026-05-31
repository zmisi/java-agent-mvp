package com.example.javaagentmvp.chat.context;

import com.example.javaagentmvp.chat.persistence.mapper.ConversationTurnSummaryMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConversationTurnSummaryBuffer {

    private static final int MAX_PER_CONVERSATION = 120;
    private static final int GOAL_MAX_CHARS = 100;
    private static final int FINDING_MAX_CHARS = 100;

    private final ConversationTurnSummaryMapper mapper;

    public ConversationTurnSummaryBuffer(ConversationTurnSummaryMapper mapper) {
        this.mapper = mapper;
    }

    public void appendTurn(String conversationId, String userMessage, String assistantReply) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String goal = trimOneLine(userMessage, GOAL_MAX_CHARS);
        String finding = extractSeveralSentences(assistantReply, 3, FINDING_MAX_CHARS);
        if (goal.isBlank() && finding.isBlank()) {
            return;
        }
        if (goal.isBlank()) {
            goal = "(none)";
        }
        if (finding.isBlank()) {
            finding = "(none)";
        }
        String row = "goal=" + goal + " ; finding=" + finding;
        mapper.insertNextTurnSummary(conversationId, goal, finding, row);
    }

    public List<String> recent(String conversationId, int limit) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0) {
            return List.of();
        }
        int safeLimit = Math.min(limit, MAX_PER_CONVERSATION);
        return mapper.selectRecentSummaryRows(conversationId, safeLimit);
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
