package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RuleBasedSummaryGenerator implements ConversationSummaryGenerator {

    private static final int SUMMARY_SOURCE_LIMIT = 16;
    private static final int SUMMARY_LINE_MAX_CHARS = 120;
    private static final int FINDING_LABEL_MAX_CHARS = 80;
    private static final List<String> CONCLUSION_MARKERS = List.of("结论：", "结论:", "✅", "Summary:", "FINDING:");

    @Override
    public String generate(String conversationId, List<Message> history) {
        return extractParts(history).format();
    }

    CompactionBriefParts extractParts(List<Message> history) {
        if (history.isEmpty()) {
            return new CompactionBriefParts(List.of(), List.of());
        }
        List<String> userGoals = new ArrayList<>();
        List<String> assistantFindings = new ArrayList<>();
        int start = Math.max(0, history.size() - SUMMARY_SOURCE_LIMIT);
        for (int i = start; i < history.size(); i++) {
            Message m = history.get(i);
            MessageType type = m.getMessageType();
            if (type == MessageType.SYSTEM) {
                continue;
            }
            String text = CompactionMessageUtils.safeText(m).strip();
            if (text.isEmpty()) {
                continue;
            }
            if (type == MessageType.USER && userGoals.size() < 6) {
                String goal = CompactionMessageUtils.oneLine(text);
                if (goal.length() > SUMMARY_LINE_MAX_CHARS) {
                    goal = goal.substring(0, SUMMARY_LINE_MAX_CHARS) + "…";
                }
                userGoals.add(goal);
            } else if (type == MessageType.ASSISTANT || type == MessageType.TOOL) {
                String label = CompactionFindingSanitizer.sanitize(extractFindingLabel(text));
                if (CompactionFindingSanitizer.isAcceptable(label)
                        && !assistantFindings.contains(label)
                        && assistantFindings.size() < 8) {
                    assistantFindings.add(label);
                }
            }
        }
        return new CompactionBriefParts(userGoals, assistantFindings);
    }

    private static String extractFindingLabel(String text) {
        String oneLine = CompactionMessageUtils.oneLine(text);
        for (String marker : CONCLUSION_MARKERS) {
            int idx = oneLine.indexOf(marker);
            if (idx >= 0) {
                return truncateLabel(oneLine.substring(idx).strip());
            }
        }
        return fallbackFindingLabel(oneLine);
    }

    private static String fallbackFindingLabel(String line) {
        int period = line.indexOf('。');
        if (period > 20 && period <= FINDING_LABEL_MAX_CHARS) {
            return line.substring(0, period + 1);
        }
        if (line.length() <= FINDING_LABEL_MAX_CHARS) {
            return line;
        }
        return line.substring(0, FINDING_LABEL_MAX_CHARS) + "…";
    }

    private static String truncateLabel(String label) {
        if (label.length() <= FINDING_LABEL_MAX_CHARS) {
            return label;
        }
        return label.substring(0, FINDING_LABEL_MAX_CHARS) + "…";
    }
}
