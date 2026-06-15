package com.example.javaagentmvp.admissionworkflow;

public record SynthesisProperties(
        boolean enabled,
        String promptLocation,
        String rankPromptLocation,
        double temperature,
        boolean fallbackToSummaryOnFailure) {

    public static SynthesisProperties defaults() {
        return new SynthesisProperties(
                true,
                "classpath:prompts/admission-report-synthesis.md",
                "classpath:prompts/admission-rank-synthesis.md",
                0.3,
                true);
    }

    public SynthesisProperties {
        if (promptLocation == null || promptLocation.isBlank()) {
            promptLocation = "classpath:prompts/admission-report-synthesis.md";
        }
        if (rankPromptLocation == null || rankPromptLocation.isBlank()) {
            rankPromptLocation = "classpath:prompts/admission-rank-synthesis.md";
        }
    }
}
