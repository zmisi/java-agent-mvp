package com.example.javaagentmvp.chat.context;

import com.example.javaagentmvp.chat.PostgresChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ConversationCompactionService {

    private static final Logger log = LoggerFactory.getLogger(ConversationCompactionService.class);

    private static final int RECENT_USER_BUDGET_TOKENS = 20_000;
    private static final int AGGRESSIVE_USER_BUDGET_TOKENS = 2_000;
    private static final double MIN_EXPECTED_REDUCTION_RATIO = 0.10;

    private final PostgresChatMemory postgresChatMemory;
    private final ConversationSummaryGenerator summaryGenerator;

    public ConversationCompactionService(
            PostgresChatMemory postgresChatMemory, ConversationSummaryGenerator summaryGenerator) {
        this.postgresChatMemory = postgresChatMemory;
        this.summaryGenerator = summaryGenerator;
    }

    public CompactionResult preview(String conversationId) {
        return runCompaction(conversationId, false);
    }

    public CompactionResult compact(String conversationId) {
        return runCompaction(conversationId, true);
    }

    private CompactionResult runCompaction(String conversationId, boolean persist) {
        long startedAt = System.currentTimeMillis();
        List<Message> history = postgresChatMemory.get(conversationId);
        int beforeMessageCount = history.size();
        int beforeEstimatedTokens = estimateTokens(history);
        String summary = summaryGenerator.generate(conversationId, history);

        List<Message> compacted = CompactedHistoryBuilder.build(
                history,
                summary,
                RECENT_USER_BUDGET_TOKENS,
                InitialContextInjection.DO_NOT_INJECT,
                Optional.empty());

        int afterMessageCount = compacted.size();
        int afterEstimatedTokens = estimateTokens(compacted);
        if (shouldApplyAggressiveCompaction(beforeEstimatedTokens, afterEstimatedTokens)) {
            compacted = CompactedHistoryBuilder.build(
                    history,
                    summary,
                    AGGRESSIVE_USER_BUDGET_TOKENS,
                    InitialContextInjection.DO_NOT_INJECT,
                    Optional.empty());
            afterMessageCount = compacted.size();
            afterEstimatedTokens = estimateTokens(compacted);
        }

        if (persist) {
            postgresChatMemory.clear(conversationId);
            postgresChatMemory.add(conversationId, compacted);
        }
        log.info(
                "[COMPACT {}] conversationId={} beforeMsg={} afterMsg={} beforeTok={} afterTok={} elapsedMs={}",
                persist ? "EXECUTE" : "PREVIEW",
                conversationId,
                beforeMessageCount,
                afterMessageCount,
                beforeEstimatedTokens,
                afterEstimatedTokens,
                (System.currentTimeMillis() - startedAt));
        return new CompactionResult(
                summary,
                beforeMessageCount,
                afterMessageCount,
                beforeEstimatedTokens,
                afterEstimatedTokens);
    }

    private static int estimateTokens(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += ApproximateTokenEstimator.estimateTokens(CompactionMessageUtils.safeText(message));
        }
        return total;
    }

    private static boolean shouldApplyAggressiveCompaction(int beforeEstimatedTokens, int afterEstimatedTokens) {
        if (beforeEstimatedTokens <= 0) {
            return false;
        }
        int reduced = beforeEstimatedTokens - afterEstimatedTokens;
        double ratio = (double) reduced / (double) beforeEstimatedTokens;
        return ratio < MIN_EXPECTED_REDUCTION_RATIO;
    }

    public record CompactionResult(
            String summary,
            int beforeMessageCount,
            int afterMessageCount,
            int beforeEstimatedTokens,
            int afterEstimatedTokens
    ) {
    }
}
