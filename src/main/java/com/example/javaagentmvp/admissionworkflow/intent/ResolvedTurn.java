package com.example.javaagentmvp.admissionworkflow.intent;

/**
 * Conversation state for the current user turn: intent, merged slots, and what changed this turn.
 */
public record ResolvedTurn(
        AdmissionIntent intent,
        AdmissionInputParser.ParsedAdmissionInput slots,
        SlotDelta delta,
        boolean inheritedIntent) {

    public static ResolvedTurn unknown(String message) {
        return new ResolvedTurn(
                AdmissionIntent.UNKNOWN,
                AdmissionInputParser.parse(message),
                SlotDelta.NONE,
                false);
    }

    public boolean needsMcpTool() {
        return intent == AdmissionIntent.RANK || intent == AdmissionIntent.SCORE;
    }

    public boolean needsTaskPrompt() {
        return needsMcpTool() || intent == AdmissionIntent.POLICY;
    }

    public String preferredMcpTool() {
        return switch (intent) {
            case RANK -> "getRankByScore";
            case SCORE -> "getMajorByScore";
            default -> null;
        };
    }
}
