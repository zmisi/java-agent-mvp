package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UnsupportedConstraintIr(
        @JsonProperty("raw_phrase") String rawPhrase,
        @JsonProperty("constraint_type") String constraintType,
        @JsonProperty("reason") String reason,
        @JsonProperty("label") String label) {

    public UnsupportedConstraintIr {
        if (rawPhrase == null) {
            rawPhrase = "";
        }
        if (constraintType == null) {
            constraintType = "unknown";
        }
        if (reason == null) {
            reason = "no_data";
        }
        if (label == null || label.isBlank()) {
            label = rawPhrase;
        }
    }

    public String displayLabel() {
        return label == null || label.isBlank() ? rawPhrase : label;
    }
}
