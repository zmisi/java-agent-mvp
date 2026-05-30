package com.example.javaagentmvp.chat.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat.compaction")
public record CompactionProperties(
        CompactionSummaryMode summaryMode,
        boolean fallbackToRulesOnFailure,
        String promptLocation,
        int transcriptMessageLimit,
        int turnSummaryLimit,
        int maxCharsPerMessage,
        double temperature) {

    public CompactionProperties {
        if (summaryMode == null) {
            summaryMode = CompactionSummaryMode.MODEL;
        }
        if (promptLocation == null || promptLocation.isBlank()) {
            promptLocation = "classpath:prompts/compaction-system.md";
        }
        if (transcriptMessageLimit <= 0) {
            transcriptMessageLimit = 16;
        }
        if (turnSummaryLimit <= 0) {
            turnSummaryLimit = 24;
        }
        if (maxCharsPerMessage <= 0) {
            maxCharsPerMessage = 6_000;
        }
        if (temperature < 0) {
            temperature = 0;
        }
    }
}
