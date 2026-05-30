package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class CompactionBriefAssembler {

    private static final int MAX_FINDINGS = 8;

    private CompactionBriefAssembler() {
    }

    static String reconcile(String modelSummary, CompactionBriefParts ruleParts, List<Message> history) {
        CompactionBriefParts modelParts = CompactionBriefParser.parse(modelSummary);
        List<String> userGoals = mergeUserGoals(ruleParts.userGoals(), modelParts.userGoals(), history);
        List<String> findings = mergeFindings(
                CompactionFindingSanitizer.sanitizeList(ruleParts.knownFindings()),
                CompactionFindingSanitizer.sanitizeList(modelParts.knownFindings()),
                history);
        return new CompactionBriefParts(userGoals, findings).format();
    }

    private static List<String> mergeUserGoals(List<String> ruleGoals, List<String> modelGoals, List<Message> history) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(ruleGoals);
        int userMessageCount = countUserMessages(history);
        if (merged.size() >= Math.min(userMessageCount, 6)) {
            return List.copyOf(merged);
        }
        for (String modelGoal : modelGoals) {
            if (merged.size() >= 6) {
                break;
            }
            if (merged.stream().noneMatch(existing -> similarGoal(existing, modelGoal))) {
                merged.add(modelGoal);
            }
        }
        return List.copyOf(merged);
    }

    private static List<String> mergeFindings(
            List<String> ruleFindings, List<String> modelFindings, List<Message> history) {
        int target = Math.min(Math.max(countAssistantTurns(history), 1), MAX_FINDINGS);
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        for (String modelFinding : modelFindings) {
            addIfFits(merged, modelFinding);
            if (merged.size() >= MAX_FINDINGS) {
                return List.copyOf(merged);
            }
        }
        if (merged.size() < target) {
            for (String ruleFinding : ruleFindings) {
                addIfFits(merged, ruleFinding);
                if (merged.size() >= MAX_FINDINGS) {
                    break;
                }
            }
        }
        return List.copyOf(merged);
    }

    private static void addIfFits(Set<String> merged, String candidate) {
        if (!CompactionFindingSanitizer.isAcceptable(candidate)) {
            return;
        }
        if (merged.stream().anyMatch(existing -> redundantFinding(existing, candidate))) {
            return;
        }
        merged.add(candidate);
    }

    private static boolean redundantFinding(String left, String right) {
        String a = left.replaceAll("\\s+", "").toLowerCase();
        String b = right.replaceAll("\\s+", "").toLowerCase();
        if (a.equals(b) || a.contains(b) || b.contains(a)) {
            return true;
        }
        return tokenOverlapRatio(a, b) >= 0.55;
    }

    private static double tokenOverlapRatio(String a, String b) {
        Set<String> left = tokenize(a);
        Set<String> right = tokenize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        long shared = left.stream().filter(right::contains).count();
        return (double) shared / (double) Math.min(left.size(), right.size());
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.split("[^\\p{L}\\p{N}]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .collect(Collectors.toSet());
    }

    private static boolean similarGoal(String left, String right) {
        String a = left.replaceAll("\\s+", "");
        String b = right.replaceAll("\\s+", "");
        return a.equals(b) || a.contains(b) || b.contains(a);
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

    private static int countAssistantTurns(List<Message> history) {
        int count = 0;
        for (Message message : history) {
            MessageType type = message.getMessageType();
            if ((type == MessageType.ASSISTANT || type == MessageType.TOOL)
                    && !CompactionMessageUtils.safeText(message).strip().isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
