package com.example.javaagentmvp.admissionworkflow.compiler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.admission-compiler")
public record AdmissionCompilerProperties(
        boolean enabled,
        String baseUrl,
        int timeoutMs,
        boolean fallbackToLocal,
        boolean recordUnsupported) {

    public AdmissionCompilerProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8090";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 3_000;
        }
    }

    public static AdmissionCompilerProperties defaults() {
        return new AdmissionCompilerProperties(false, "http://localhost:8090", 3_000, true, true);
    }
}
