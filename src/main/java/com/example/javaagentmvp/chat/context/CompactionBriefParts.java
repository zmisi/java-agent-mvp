package com.example.javaagentmvp.chat.context;

import java.util.List;

record CompactionBriefParts(List<String> userGoals, List<String> knownFindings) {

    static final String CANONICAL_NEXT_STEP =
            "continue from the latest user request using this brief; confirm any missing constraints or facts noted above.";

    String format() {
        if (userGoals.isEmpty() && knownFindings.isEmpty()) {
            return "(no summary available)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Compact brief:\n");
        if (!userGoals.isEmpty()) {
            sb.append("- User goals: ").append(String.join(" | ", userGoals)).append('\n');
        }
        if (!knownFindings.isEmpty()) {
            sb.append("- Known findings: ").append(String.join(" | ", knownFindings)).append('\n');
        }
        sb.append("- Next step: ").append(CANONICAL_NEXT_STEP);
        return sb.toString().strip();
    }
}
