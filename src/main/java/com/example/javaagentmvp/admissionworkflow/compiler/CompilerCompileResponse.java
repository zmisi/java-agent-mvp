package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CompilerCompileResponse(
        @JsonProperty("query") AdmissionQueryIr query,
        @JsonProperty("schema_version") String schemaVersion) {
}
