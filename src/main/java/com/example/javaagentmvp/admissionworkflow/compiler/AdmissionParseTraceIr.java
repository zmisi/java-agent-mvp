package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdmissionParseTraceIr(
        @JsonProperty("rules_applied") List<String> rulesApplied,
        @JsonProperty("ontology_hits") List<String> ontologyHits,
        @JsonProperty("llm_used") boolean llmUsed,
        @JsonProperty("inherited_from_prior") boolean inheritedFromPrior) {

    public AdmissionParseTraceIr(List<String> rulesApplied, List<String> ontologyHits, boolean llmUsed) {
        this(rulesApplied, ontologyHits, llmUsed, false);
    }
}
