package com.example.javaagentmvp.chat.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class CompactedHistoryBuilder {

    public static final String SUMMARY_PREFIX = "Summary of previous conversation:";
    private static final String TRUNCATED_MARKER = "\n...[truncated]...\n";

    private CompactedHistoryBuilder() {
    }

    public static List<Message> build(
            List<Message> history,
            String summary,
            int recentUserBudgetTokens,
            InitialContextInjection injection,
            Optional<List<Message>> initialContext
    ) {
        List<String> userMessages = collectRealUserMessages(history);
        List<String> retainedUsers = retainRecentUserMessagesByBudget(userMessages, recentUserBudgetTokens);

        List<Message> replacement = new ArrayList<>();
        for (String text : retainedUsers) {
            replacement.add(new UserMessage(text));
        }

        String finalSummary = (summary == null || summary.isBlank()) ? "(no summary available)" : summary.trim();
        replacement.add(new UserMessage(SUMMARY_PREFIX + "\n" + finalSummary));

        if (injection == InitialContextInjection.BEFORE_LAST_USER
                && initialContext.isPresent()
                && !initialContext.get().isEmpty()) {
            replacement = insertInitialContextBeforeLastRealUserOrSummary(replacement, initialContext.get());
        }

        return replacement;
    }

    private static List<String> collectRealUserMessages(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (Message m : history) {
            if (m.getMessageType() != MessageType.USER) {
                continue;
            }
            String text = CompactionMessageUtils.safeText(m);
            if (text.isBlank() || text.startsWith(SUMMARY_PREFIX + "\n")) {
                continue;
            }
            out.add(text);
        }
        return out;
    }

    private static List<String> retainRecentUserMessagesByBudget(List<String> messages, int budgetTokens) {
        if (messages.isEmpty() || budgetTokens <= 0) {
            return Collections.emptyList();
        }
        int remaining = budgetTokens;
        List<String> reversed = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            String msg = messages.get(i);
            int tokens = ApproximateTokenEstimator.estimateTokens(msg);
            if (tokens <= remaining) {
                reversed.add(msg);
                remaining -= tokens;
                continue;
            }
            if (remaining > 0) {
                int maxChars = Math.max(1, remaining * 4);
                reversed.add(msg.length() <= maxChars ? msg : msg.substring(0, maxChars) + TRUNCATED_MARKER);
            }
            break;
        }
        Collections.reverse(reversed);
        return reversed;
    }

    private static List<Message> insertInitialContextBeforeLastRealUserOrSummary(
            List<Message> compacted,
            List<Message> initialContext
    ) {
        int lastUserLikeIdx = -1;
        int lastRealUserIdx = -1;
        for (int i = compacted.size() - 1; i >= 0; i--) {
            Message m = compacted.get(i);
            if (m.getMessageType() != MessageType.USER) {
                continue;
            }
            lastUserLikeIdx = Math.max(lastUserLikeIdx, i);
            String text = CompactionMessageUtils.safeText(m);
            if (!text.startsWith(SUMMARY_PREFIX + "\n")) {
                lastRealUserIdx = i;
                break;
            }
        }
        int insertAt = (lastRealUserIdx != -1)
                ? lastRealUserIdx
                : (lastUserLikeIdx != -1 ? lastUserLikeIdx : compacted.size());
        List<Message> out = new ArrayList<>(compacted.size() + initialContext.size());
        out.addAll(compacted.subList(0, insertAt));
        out.addAll(initialContext);
        out.addAll(compacted.subList(insertAt, compacted.size()));
        return out;
    }
}
