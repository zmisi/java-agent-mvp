package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdmissionPreferenceIr(
        @JsonProperty("dimension") String dimension,
        @JsonProperty("weight") double weight,
        @JsonProperty("raw_phrase") String rawPhrase) {
}
