package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdmissionRegionIr(
        @JsonProperty("phrase") String phrase,
        @JsonProperty("provinces") List<String> provinces) {
}
