package com.example.javaagentmvp.admissionworkflow.format;

import java.util.Set;

/** Maps province gaokao models to the subject_group filter used by getRankByScore. */
public final class RankSubjectGroupResolver {

    /** Provinces that publish score segments under 综合类 only (3+3 新高考). */
    private static final Set<String> COMPREHENSIVE_ONLY_PROVINCES = Set.of(
            "浙江", "上海", "北京", "天津", "山东", "海南");

    private RankSubjectGroupResolver() {
    }

    public static boolean usesComprehensiveSubjectGroup(String province) {
        return province != null && COMPREHENSIVE_ONLY_PROVINCES.contains(province.strip());
    }

    /**
     * Subject group to pass to getRankByScore for the given province, or {@code null} to omit the filter.
     * Split-subject provinces (e.g. 江苏、安徽) honor the requested 物理类/历史类; 综合类 provinces ignore it.
     */
    public static String rankSubjectGroupForProvince(String province, String requestedSubjectGroup) {
        if (usesComprehensiveSubjectGroup(province)) {
            return null;
        }
        if (requestedSubjectGroup == null || requestedSubjectGroup.isBlank()) {
            return null;
        }
        return requestedSubjectGroup.strip();
    }
}
