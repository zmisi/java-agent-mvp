package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdmissionFiltersIr(
        @JsonProperty("exclude_school_name_contains") List<String> excludeSchoolNameContains,
        @JsonProperty("exclude_major_keywords") List<String> excludeMajorKeywords,
        @JsonProperty("include_major_keywords") List<String> includeMajorKeywords,
        @JsonProperty("include_schools") List<String> includeSchools,
        @JsonProperty("include_major_discipline_groups") List<String> includeMajorDisciplineGroups,
        @JsonProperty("include_discipline_categories") List<String> includeDisciplineCategories) {

    public AdmissionFiltersIr {
        if (excludeSchoolNameContains == null) {
            excludeSchoolNameContains = List.of();
        }
        if (excludeMajorKeywords == null) {
            excludeMajorKeywords = List.of();
        }
        if (includeMajorKeywords == null) {
            includeMajorKeywords = List.of();
        }
        if (includeSchools == null) {
            includeSchools = List.of();
        }
        if (includeMajorDisciplineGroups == null) {
            includeMajorDisciplineGroups = List.of();
        }
        if (includeDisciplineCategories == null) {
            includeDisciplineCategories = List.of();
        }
    }

    public static AdmissionFiltersIr empty() {
        return new AdmissionFiltersIr(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean hasMajorCategoryFilter() {
        return !includeMajorDisciplineGroups.isEmpty() || !includeDisciplineCategories.isEmpty();
    }
}
