package com.example.javaagentmvp.admissionworkflow;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.admission-workflow")
public record AdmissionWorkflowProperties(
        boolean enabled,
        String defaultWorkflowType,
        boolean logCheckpoints,
        SynthesisProperties synthesis) {

    public AdmissionWorkflowProperties {
        if (defaultWorkflowType == null || defaultWorkflowType.isBlank()) {
            defaultWorkflowType = "admission_report";
        }
        if (synthesis == null) {
            synthesis = SynthesisProperties.defaults();
        }
    }
}
