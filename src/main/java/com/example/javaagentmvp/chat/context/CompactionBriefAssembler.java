package com.example.javaagentmvp.chat.context;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class CompactionBriefAssembler {

    private static final int MAX_FINDINGS_HARD_CAP = 48;

    private CompactionBriefAssembler() {}

    static String reconcile(String modelSummary, CompactionBriefParts ruleParts, List<org.springframework.ai.chat.messages.Message> history) {
        CompactionBriefParts modelParts = CompactionBriefParser.parse(modelSummary);
        List<String> userGoals = mergeUserGoals(ruleParts.userGoals(), modelParts.userGoals(), history);
        List<String> findings = mergeFindings(
                CompactionFindingSanitizer.sanitizeList(ruleParts.knownFindings()),
                CompactionFindingSanitizer.sanitizeList(modelParts.knownFindings()),
                userGoals.size(),
                history);
        return new CompactionBriefParts(userGoals, findings).format();
    }

    private static List<String> mergeUserGoals(
            List<String> ruleGoals, List<String> modelGoals, List<org.springframework.ai.chat.messages.Message> history) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String ruleGoal : ruleGoals) {
            if (merged.stream().noneMatch(existing -> similarGoal(existing, ruleGoal))) {
                merged.add(ruleGoal);
            }
        }
        for (String modelGoal : modelGoals) {
            if (merged.stream().noneMatch(existing -> similarGoal(existing, modelGoal))) {
                merged.add(modelGoal);
            }
        }
        int target = countUserMessages(history);
        if (target > 0 && merged.size() > target) {
            return List.copyOf(new java.util.ArrayList<>(merged).subList(0, target));
        }
        return List.copyOf(merged);
    }

    private static List<String> mergeFindings(
            List<String> ruleFindings,
            List<String> modelFindings,
            int goalCount,
            List<org.springframework.ai.chat.messages.Message> history) {
        int maxTarget = Math.max(1, (int) Math.ceil(goalCount * 1.5));
        int minTarget = Math.max(1, goalCount);
        int assistantTurns = Math.max(1, countAssistantTurns(history));
        int effectiveMin = Math.min(minTarget, assistantTurns);
        LinkedHashSet<String> merged = new LinkedHashSet<>();

        for (String modelFinding : modelFindings) {
            addIfFits(merged, modelFinding);
            if (merged.size() >= maxTarget || merged.size() >= MAX_FINDINGS_HARD_CAP) {
                return List.copyOf(merged);
            }
        }
        if (merged.size() < effectiveMin) {
            for (String ruleFinding : ruleFindings) {
                addIfFits(merged, ruleFinding);
                if (merged.size() >= maxTarget || merged.size() >= MAX_FINDINGS_HARD_CAP) {
                    break;
                }
            }
        }
        if (merged.size() > maxTarget) {
            return List.copyOf(new java.util.ArrayList<>(merged).subList(0, maxTarget));
        }
        return List.copyOf(merged);
    }

    private static void addIfFits(Set<String> merged, String candidate) {
        if (!CompactionFindingSanitizer.isAcceptable(candidate)) return;
        if (merged.stream().anyMatch(existing -> redundantFinding(existing, candidate))) return;
        merged.add(candidate);
    }

    private static boolean redundantFinding(String left, String right) {
        String a = left.replaceAll("\\s+", "").toLowerCase();
        String b = right.replaceAll("\\s+", "").toLowerCase();
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        return tokenOverlapRatio(a, b) >= 0.55;
    }

    private static double tokenOverlapRatio(String a, String b) {
        Set<String> left = tokenize(a);
        Set<String> right = tokenize(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
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
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        // fuzzy: high token overlap = same intent
        return tokenOverlapRatio(a, b) >= 0.7;
    }

    private static int countUserMessages(List<org.springframework.ai.chat.messages.Message> history) {
        int count = 0;
        for (org.springframework.ai.chat.messages.Message message : history) {
            if (message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER
                    && !CompactionMessageUtils.safeText(message).strip().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int countAssistantTurns(List<org.springframework.ai.chat.messages.Message> history) {
        int count = 0;
        for (org.springframework.ai.chat.messages.Message message : history) {
            org.springframework.ai.chat.messages.MessageType type = message.getMessageType();
            if ((type == org.springframework.ai.chat.messages.MessageType.ASSISTANT
                    || type == org.springframework.ai.chat.messages.MessageType.TOOL)
                    && !CompactionMessageUtils.safeText(message).strip().isEmpty()) {
                count++;
            }
        }
        return count;
    }

}
