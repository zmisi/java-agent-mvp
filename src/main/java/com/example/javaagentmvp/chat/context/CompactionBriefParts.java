package com.example.javaagentmvp.chat.context;

import java.util.List;

record CompactionBriefParts(List<String> userGoals, List<String> knownFindings) {

    static final String CANONICAL_NEXT_STEP =
            "continue from the latest user request using this brief; confirm any missing constraints or facts noted above.";

    String format() {
        List<String> goals = normalizedGoals(userGoals);
        List<String> findings = normalizedFindings(knownFindings);
        if (goals.isEmpty() && findings.isEmpty()) {
            return "(no summary available)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Compact brief:\n");
        if (!goals.isEmpty()) {
            sb.append("- User goals: ").append(String.join(" | ", goals)).append('\n');
        }
        if (!findings.isEmpty()) {
            sb.append("- Known findings: ").append(String.join(" | ", findings)).append('\n');
        }
        sb.append("- Next step: ").append(CANONICAL_NEXT_STEP);
        return sb.toString().strip();
    }

    private static List<String> normalizedGoals(List<String> rawGoals) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String goal : rawGoals) {
            String line = CompactionMessageUtils.oneLine(goal);
            if (!line.isBlank()) {
                out.add(CompactionMessageUtils.abbreviateMiddle(line, 120));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> normalizedFindings(List<String> rawFindings) {
        return CompactionFindingSanitizer.sanitizeList(rawFindings).stream()
                .limit(CompactionFindingSanitizer.MAX_FINDINGS)
                .toList();
    }
}
