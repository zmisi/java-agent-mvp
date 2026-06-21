package com.example.javaagentmvp.admissionworkflow.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AdmissionSlotsIr(
        @JsonProperty("score") Integer score,
        @JsonProperty("rank") Integer rank,
        @JsonProperty("provinces") List<String> provinces,
        @JsonProperty("subject_group") String subjectGroup,
        @JsonProperty("year") Integer year,
        @JsonProperty("admission_type") String admissionType) {

    public AdmissionSlotsIr {
        if (provinces == null) {
            provinces = List.of();
        }
    }

    public static AdmissionSlotsIr empty() {
        return new AdmissionSlotsIr(null, null, List.of(), null, null, null);
    }

    public String primaryProvince() {
        return provinces.isEmpty() ? null : provinces.get(0);
    }

    public List<String> provincesOrEmpty() {
        return provinces == null ? List.of() : List.copyOf(provinces);
    }

    public AdmissionSlotsIr withoutProvinces() {
        return new AdmissionSlotsIr(score, rank, List.of(), subjectGroup, year, admissionType);
    }

    public AdmissionSlotsIr mergedWith(AdmissionSlotsIr prior) {
        if (prior == null) {
            return this;
        }
        List<String> mergedProvinces = new ArrayList<>(prior.provincesOrEmpty());
        for (String province : provincesOrEmpty()) {
            if (!mergedProvinces.contains(province)) {
                mergedProvinces.add(province);
            }
        }
        return new AdmissionSlotsIr(
                score != null ? score : prior.score(),
                rank != null ? rank : prior.rank(),
                mergedProvinces,
                subjectGroup != null ? subjectGroup : prior.subjectGroup(),
                year != null ? year : prior.year(),
                admissionType != null ? admissionType : prior.admissionType());
    }

    public boolean hasScoreOrRank() {
        return score != null || rank != null;
    }
}
