package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdmissionQueryIr(
        @JsonProperty("task") String task,
        @JsonProperty("slots") AdmissionSlotsIr slots,
        @JsonProperty("filters") AdmissionFiltersIr filters,
        @JsonProperty("preferences") List<AdmissionPreferenceIr> preferences,
        @JsonProperty("regions") List<AdmissionRegionIr> regions,
        @JsonProperty("unsupported_constraints") List<UnsupportedConstraintIr> unsupportedConstraints,
        @JsonProperty("needs_clarification") List<String> needsClarification,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("raw_message") String rawMessage,
        @JsonProperty("parse_trace") AdmissionParseTraceIr parseTrace) {

    public AdmissionQueryIr {
        if (slots == null) {
            slots = AdmissionSlotsIr.empty();
        }
        if (filters == null) {
            filters = AdmissionFiltersIr.empty();
        }
        if (preferences == null) {
            preferences = List.of();
        }
        if (regions == null) {
            regions = List.of();
        }
        if (unsupportedConstraints == null) {
            unsupportedConstraints = List.of();
        }
        if (needsClarification == null) {
            needsClarification = List.of();
        }
    }

    public boolean hasUnsupportedConstraints() {
        return unsupportedConstraints != null && !unsupportedConstraints.isEmpty();
    }

    public AdmissionIntent toIntent() {
        return switch (task == null ? "" : task) {
            case "search_majors" -> AdmissionIntent.SCORE;
            case "search_rank" -> AdmissionIntent.RANK;
            case "policy_qa" -> AdmissionIntent.POLICY;
            case "report" -> AdmissionIntent.REPORT;
            default -> AdmissionIntent.UNKNOWN;
        };
    }

    public boolean blocksMcpExecution() {
        if (needsClarification == null || needsClarification.isEmpty()) {
            return false;
        }
        AdmissionIntent intent = toIntent();
        if (intent == AdmissionIntent.SCORE || intent == AdmissionIntent.REPORT) {
            return needsClarification.contains("score")
                    || needsClarification.contains("provinces")
                    || needsClarification.contains("subject_group")
                    || needsClarification.contains(MajorCategoryClarificationSupport.FIELD);
        }
        if (intent == AdmissionIntent.RANK) {
            return needsClarification.stream().anyMatch("score"::equals);
        }
        return false;
    }

    public static AdmissionQueryIr empty(String message) {
        return new AdmissionQueryIr(
                "unknown",
                AdmissionSlotsIr.empty(),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.0,
                message,
                null);
    }
}
