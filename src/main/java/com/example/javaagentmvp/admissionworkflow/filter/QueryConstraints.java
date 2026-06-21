package com.example.javaagentmvp.admissionworkflow.filter;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionFiltersIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPreferenceIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionQueryHints;
import com.example.javaagentmvp.admissionworkflow.planner.QueryPlanner;
import com.example.javaagentmvp.rag.RagSource;

import java.util.List;

public record QueryConstraints(
        List<String> provinces,
        List<String> excludeSchoolNameContains,
        List<String> excludeMajorKeywords,
        List<String> includeMajorKeywords,
        List<String> includeMajorDisciplineGroups,
        List<String> includeDisciplineCategories,
        List<AdmissionPreferenceIr> preferences,
        List<String> preferenceBoostKeywords) {

    public static QueryConstraints fromIr(AdmissionQueryIr query, AdmissionQueryHints.Hints hints) {
        AdmissionFiltersIr filters = query == null ? AdmissionFiltersIr.empty() : query.filters();
        List<String> includeMajors = filters.includeMajorKeywords().isEmpty()
                ? (hints == null ? List.of() : hints.majorKeywords())
                : filters.includeMajorKeywords();
        List<String> provinces = query == null || query.slots().provincesOrEmpty().isEmpty()
                ? List.of()
                : query.slots().provincesOrEmpty();
        List<AdmissionPreferenceIr> preferences = query == null ? List.of() : query.preferences();
        return new QueryConstraints(
                provinces,
                filters.excludeSchoolNameContains(),
                filters.excludeMajorKeywords(),
                includeMajors,
                filters.includeMajorDisciplineGroups(),
                filters.includeDisciplineCategories(),
                preferences,
                QueryPlanner.preferenceMajorBoostKeywords(query));
    }

    public boolean hasProvinceFilter() {
        return provinces != null && !provinces.isEmpty();
    }

    public boolean hasExclusions() {
        return (excludeSchoolNameContains != null && !excludeSchoolNameContains.isEmpty())
                || (excludeMajorKeywords != null && !excludeMajorKeywords.isEmpty());
    }

    public boolean hasMajorCategoryFilter() {
        return (includeMajorDisciplineGroups != null && !includeMajorDisciplineGroups.isEmpty())
                || (includeDisciplineCategories != null && !includeDisciplineCategories.isEmpty());
    }

    public boolean hasPreferenceRanking() {
        return preferences != null && !preferences.isEmpty();
    }

    public boolean matchesExclusionContext(RagSource source) {
        if (source == null || !hasPreferenceRanking()) {
            return false;
        }
        String haystack = (source.title() + " " + source.snippet()).toLowerCase();
        for (String term : QueryPlanner.preferenceTerms(preferences.get(0).dimension())) {
            if (haystack.contains(term.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
