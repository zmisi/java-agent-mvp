package com.example.javaagentmvp.chat.context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompactionSummaryNormalizer {

    private static final Pattern FENCED_BLOCK =
            Pattern.compile("^```(?:markdown|text)?\\s*([\\s\\S]*?)```\\s*$", Pattern.CASE_INSENSITIVE);

    private CompactionSummaryNormalizer() {
    }

    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.strip();
        Matcher matcher = FENCED_BLOCK.matcher(text);
        if (matcher.matches()) {
            text = matcher.group(1).strip();
        }
        return text.strip();
    }
}
