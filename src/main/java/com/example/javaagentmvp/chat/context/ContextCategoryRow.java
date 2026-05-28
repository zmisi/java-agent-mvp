package com.example.javaagentmvp.chat.context;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One slice of the estimated prompt budget (Cursor-style breakdown row).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextCategoryRow(
        String category,
        String label,
        int estimatedTokens,
        Integer charCount) {
}
