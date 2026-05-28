package com.example.javaagentmvp.chat.context;

/**
 * Fast, tokenizer-free estimate of LLM input tokens for observability only.
 * <p>
 * Blends Latin (~4 chars/token) and non-ASCII (~1.7 chars/token for CJK-heavy text).
 * Real providers use model-specific tokenizers; this is intentionally cheap and stable.
 */
public final class ApproximateTokenEstimator {

    public static final String METHOD_ID = "ascii_div4_plus_nonAscii_div1_7";

    private ApproximateTokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                nonAscii++;
            }
        }
        int ascii = text.length() - nonAscii;
        double est = ascii / 4.0 + nonAscii / 1.7;
        return Math.max(0, (int) Math.ceil(est));
    }
}
