package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedSummaryGenerator implements ConversationSummaryGenerator {

    private static final int SUMMARY_SOURCE_LIMIT = 16;
    private static final int MAX_FINDINGS_HARD_CAP = 48;
    private static final int SUMMARY_LINE_MAX_CHARS = 120;
    private static final int FINDING_LABEL_MAX_CHARS = 80;
    private static final List<String> CONCLUSION_MARKERS = List.of(
            "结论：", "结论:", "✅", "Summary:", "FINDING:",
            "💡", "📌", "🔍");
    private static final Pattern SCORE_PATTERN =
            Pattern.compile("\\d{3}分");

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
            if (type == MessageType.USER) {
                String candidateGoal = CompactionMessageUtils.oneLine(text);
                if (candidateGoal.length() > SUMMARY_LINE_MAX_CHARS) {
                    String summarized = candidateGoal;
                    for (String q : new String[]{"什么", "如何", "怎么", "？", "?"}) {
                        int idx = candidateGoal.indexOf(q);
                        if (idx > 0 && idx < SUMMARY_LINE_MAX_CHARS) {
                            int ctxStart = Math.max(0, idx - 15);
                            summarized = (ctxStart > 0 ? "..." : "")
                                    + candidateGoal.substring(ctxStart, Math.min(candidateGoal.length(), idx + q.length() + 20));
                            break;
                        }
                    }
                    candidateGoal = CompactionMessageUtils.abbreviateMiddle(summarized, SUMMARY_LINE_MAX_CHARS);
                }
                final String goal = candidateGoal;
                if (userGoals.stream().noneMatch(existing -> similarGoal(existing, goal))) {
                    userGoals.add(goal);
                }
            } else if (type == MessageType.ASSISTANT || type == MessageType.TOOL) {
                List<String> labels = extractAllFindingLabels(text);
                for (String raw : labels) {
                    String label = CompactionFindingSanitizer.sanitize(raw);
                    if (CompactionFindingSanitizer.isAcceptable(label)
                            && !assistantFindings.contains(label)
                            && assistantFindings.size() < MAX_FINDINGS_HARD_CAP) {
                        assistantFindings.add(label);
                    }
                }
            }
        }
        int dynamicMax = Math.max(1, (int) Math.ceil(userGoals.size() * 1.5));
        if (assistantFindings.size() > dynamicMax) {
            assistantFindings = new ArrayList<>(assistantFindings.subList(0, dynamicMax));
        }
        return new CompactionBriefParts(userGoals, assistantFindings);
    }

    private static boolean similarGoal(String left, String right) {
        String a = left.replaceAll("\\s+", "");
        String b = right.replaceAll("\\s+", "");
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private static List<String> extractAllFindingLabels(String text) {
        String oneLine = CompactionMessageUtils.oneLine(text);
        List<String> results = new ArrayList<>();

        for (String marker : CONCLUSION_MARKERS) {
            int searchFrom = 0;
            while (true) {
                int idx = oneLine.indexOf(marker, searchFrom);
                if (idx < 0) {
                    break;
                }
                String label = extractFromMarker(oneLine, idx + marker.length());
                if (!label.isEmpty() && results.stream().noneMatch(
                        existing -> existing.contains(label) || label.contains(existing))) {
                    results.add(label);
                }
                searchFrom = idx + marker.length();
            }
            if (results.size() >= 2) {
                break;
            }
        }

        if (results.isEmpty()) {
            String fallback = fallbackFindingLabel(oneLine);
            if (!fallback.isEmpty()) {
                results.add(fallback);
            }
        }

        Matcher scoreMatcher = SCORE_PATTERN.matcher(oneLine);
        boolean hasScore = false;
        for (String existing : results) {
            if (SCORE_PATTERN.matcher(existing).find()) {
                hasScore = true;
                break;
            }
        }
        if (!hasScore && scoreMatcher.find()) {
            int ctxStart = Math.max(0, scoreMatcher.start() - 12);
            int ctxEnd = Math.min(oneLine.length(), scoreMatcher.end() + 20);
            String scoreLabel = oneLine.substring(ctxStart, ctxEnd).strip()
                    .replaceFirst("^[\\u3002\\uff0c,\\s]+", "");
            scoreLabel = scoreLabel.length() <= FINDING_LABEL_MAX_CHARS
                    ? scoreLabel
                    : scoreLabel.substring(0, FINDING_LABEL_MAX_CHARS) + "...";
            results.add(scoreLabel);
        }

        return results;
    }

    private static String extractFromMarker(String oneLine, int startIdx) {
        if (startIdx >= oneLine.length()) {
            return "";
        }
        int nextMarker = oneLine.length();
        for (String marker : CONCLUSION_MARKERS) {
            int idx = oneLine.indexOf(marker, startIdx);
            if (idx >= 0 && idx < nextMarker) {
                nextMarker = idx;
            }
        }
        String label = oneLine.substring(startIdx, nextMarker).strip();
        label = label.replaceFirst("^[\\u4e00-\\u4e5d\\u62fe\\u767e\\u5343]+[\\u3001.\\uff0e]?\\s*", "");
        label = label.replaceFirst("^\\d+[.\\u3001\\uff0e]\\s*", "");
        return label.strip();
    }

    private static String fallbackFindingLabel(String line) {
        String oneLine = CompactionMessageUtils.oneLine(line);
        String substantive = skipPreamble(oneLine);
        int period = substantive.indexOf('。');
        if (period > 20 && period <= FINDING_LABEL_MAX_CHARS) {
            return substantive.substring(0, period + 1);
        }
        if (substantive.length() <= FINDING_LABEL_MAX_CHARS) {
            return substantive;
        }
        return substantive.substring(0, FINDING_LABEL_MAX_CHARS) + "...";
    }

    private static String skipPreamble(String text) {
        if (text.isEmpty()) {
            return text;
        }
        String remaining = text;
        for (int attempt = 0; attempt < 3; attempt++) {
            int period = findFirstPeriod(remaining);
            if (period < 0) {
                break;
            }
            String firstSentence = remaining.substring(0, period + 1).strip();
            if (!isPreamble(firstSentence)) {
                break;
            }
            remaining = remaining.substring(period + 1).strip();
        }
        return remaining;
    }

    private static boolean isPreamble(String sentence) {
        String s = sentence.replaceAll("\\s+", "");
        if (s.isEmpty()) return false;
        if (s.startsWith("好的") || s.startsWith("以下是") ||
            s.startsWith("让我们") || s.startsWith("首先") ||
            s.startsWith("以下按")) {
            return true;
        }
        return sentence.length() < 50
            && !sentence.contains("省")
            && !sentence.contains("校")
            && (sentence.contains("之一") || sentence.contains("介绍"));
    }

    private static int findFirstPeriod(String text) {
        int cn = text.indexOf('。');
        int en = text.indexOf('.');
        if (cn < 0) return en;
        if (en < 0) return cn;
        return Math.min(cn, en);
    }
}
