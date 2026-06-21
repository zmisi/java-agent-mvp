package com.example.javaagentmvp.admissionworkflow.intent;

import com.example.javaagentmvp.rag.RagProperties;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Policy / brochure keyword detection shared by workflow nodes. */
public final class PolicyKeywordSupport {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则");

    private PolicyKeywordSupport() {
    }

    public static boolean hasPolicyKeywords(String message, RagProperties ragProperties) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty()) {
            return false;
        }
        return POLICY_PATTERN.matcher(normalized).find()
                || matchesRagIntentKeywords(normalized, ragProperties.admissions().intentKeywords());
    }

    private static boolean matchesRagIntentKeywords(String normalized, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
