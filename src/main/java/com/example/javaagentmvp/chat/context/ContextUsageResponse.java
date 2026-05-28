package com.example.javaagentmvp.chat.context;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Estimated input context for the last model call (observability; not billing-grade).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextUsageResponse(
        String estimationMethod,
        int contextWindowTokens,
        int totalEstimatedInputTokens,
        double usedPercent,
        List<ContextCategoryRow> breakdown) {
}
