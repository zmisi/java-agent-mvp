package com.example.javaagentmvp.chat.context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompactionBriefParser {

    private static final Pattern USER_GOALS_LINE =
            Pattern.compile("- User goals:\\s*(.+?)(?=\\n- |\\z)", Pattern.DOTALL);
    private static final Pattern KNOWN_FINDINGS_LINE =
            Pattern.compile("- Known findings:\\s*(.+?)(?=\\n- |\\z)", Pattern.DOTALL);

    private CompactionBriefParser() {
    }

    static CompactionBriefParts parse(String summary) {
        if (summary == null || summary.isBlank()) {
            return new CompactionBriefParts(List.of(), List.of());
        }
        return new CompactionBriefParts(
                extractSection(summary, USER_GOALS_LINE),
                extractSection(summary, KNOWN_FINDINGS_LINE));
    }

    private static List<String> extractSection(String summary, Pattern linePattern) {
        Matcher matcher = linePattern.matcher(summary);
        if (!matcher.find()) {
            return List.of();
        }
        String body = matcher.group(1).replace('\n', ' ').strip();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : body.split("\\|")) {
            String item = part.strip();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return items;
    }
}
