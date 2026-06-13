package com.example.javaagentmvp.admissionworkflow;

public record SynthesisProperties(
        boolean enabled,
        String promptLocation,
        double temperature,
        boolean fallbackToSummaryOnFailure) {

    public static SynthesisProperties defaults() {
        return new SynthesisProperties(
                true,
                "classpath:prompts/admission-report-synthesis.md",
                0.3,
                true);
    }

    public SynthesisProperties {
        if (promptLocation == null || promptLocation.isBlank()) {
            promptLocation = "classpath:prompts/admission-report-synthesis.md";
        }
    }
}
