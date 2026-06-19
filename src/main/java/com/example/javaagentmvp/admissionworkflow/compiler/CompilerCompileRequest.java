package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CompilerCompileRequest(
        @JsonProperty("message") String message,
        @JsonProperty("prior_slots") AdmissionSlotsIr priorSlots,
        @JsonProperty("prior_user_messages") List<String> priorUserMessages,
        @JsonProperty("use_llm") Boolean useLlm) {

    public static CompilerCompileRequest of(String message) {
        return new CompilerCompileRequest(message, null, List.of(), false);
    }

    public static CompilerCompileRequest of(
            String message,
            AdmissionSlotsIr priorSlots,
            List<String> priorUserMessages) {
        return new CompilerCompileRequest(
                message,
                priorSlots,
                priorUserMessages == null ? List.of() : List.copyOf(priorUserMessages),
                false);
    }
}
