package com.example.javaagentmvp.chat.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ModelSummaryGenerator {

    private static final Logger log = LoggerFactory.getLogger(ModelSummaryGenerator.class);

    private final ChatClient compactionChatClient;
    private final CompactionProperties properties;
    private final RuleBasedSummaryGenerator ruleBasedSummaryGenerator;
    private final ConversationTurnSummaryBuffer turnSummaryBuffer;
    private final String systemPrompt;

    public ModelSummaryGenerator(
            @Qualifier("compactionChatClient") ChatClient compactionChatClient,
            CompactionProperties properties,
            RuleBasedSummaryGenerator ruleBasedSummaryGenerator,
            ConversationTurnSummaryBuffer turnSummaryBuffer,
            ResourceLoader resourceLoader) {
        this.compactionChatClient = compactionChatClient;
        this.properties = properties;
        this.ruleBasedSummaryGenerator = ruleBasedSummaryGenerator;
        this.turnSummaryBuffer = turnSummaryBuffer;
        this.systemPrompt = loadSystemPrompt(properties.promptLocation(), resourceLoader);
    }

    public String generate(String conversationId, List<Message> history) {
        if (history.isEmpty()) {
            return "(no summary available)";
        }
        String transcript = CompactionTranscriptFormatter.format(
                history, properties.transcriptMessageLimit(), properties.maxCharsPerMessage());
        if (transcript.isBlank()) {
            return "(no summary available)";
        }

        log.info("[COMPACT] model-summary request messages={} transcriptChars={}", history.size(), transcript.length());
        String turnSummarySection = formatTurnSummaries(conversationId);

        String raw = compactionChatClient.prompt()
                .system(systemPrompt)
                .user("""
                        Summarize the conversation transcript below into the required Compact brief.
                        User goals: include all distinct user turns (deduplicated), in order.
                        Known findings: keep count between 1x and 1.5x of user-goal count; each <= 60 chars; one line, separated by " | " only.
                        Keep findings concise labels, not copied paragraphs.

                        --- accumulated turn summaries ---
                        %s
                        --- end ---

                        --- transcript ---
                        %s
                        --- end ---
                        """.formatted(turnSummarySection, transcript))
                .call()
                .content();

        String modelSummary = CompactionSummaryNormalizer.normalize(raw);
        CompactionBriefParts ruleParts = ruleBasedSummaryGenerator.extractParts(history);
        String summary = CompactionBriefAssembler.reconcile(modelSummary, ruleParts, history);
        if (!CompactionSummaryValidator.isValid(summary, history)) {
            throw new IllegalStateException("Model returned an invalid compact brief");
        }
        log.info("[COMPACT] model-summary ok chars={}", summary.length());
        return summary;
    }

    private String formatTurnSummaries(String conversationId) {
        List<String> rows = turnSummaryBuffer.recent(conversationId, properties.turnSummaryLimit());
        if (rows.isEmpty()) {
            return "(none)";
        }
        return String.join("\n", rows);
    }

    private static String loadSystemPrompt(String location, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Compaction prompt not found: " + location);
        }
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).strip();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load compaction prompt: " + location, ex);
        }
    }
}
