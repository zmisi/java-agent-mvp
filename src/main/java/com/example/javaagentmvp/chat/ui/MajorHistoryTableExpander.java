package com.example.javaagentmvp.chat.ui;

import com.example.javaagentmvp.admissionworkflow.DefaultAdmissionYear;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Inserts prior-year rows beneath each current-year major row for UI comparison. */
public final class MajorHistoryTableExpander {

    public static final String ROW_KIND = "_row_kind";
    public static final String ROW_KIND_CURRENT = "current";
    public static final String ROW_KIND_HISTORY = "history";
    public static final String PLAN_DELTA = "plan_delta";
    public static final String CAMPUS_CHANGE = "campus_change";

    private MajorHistoryTableExpander() {
    }

    public static boolean isHistoryRow(Map<String, String> row) {
        if (row == null) {
            return false;
        }
        if (ROW_KIND_HISTORY.equals(row.get(ROW_KIND)) || ROW_KIND_HISTORY.equals(row.get("row_kind"))) {
            return true;
        }
        String majorName = normalize(row.get("major_name"));
        String year = normalize(row.get("year"));
        if (majorName.isEmpty() || year.isEmpty()) {
            return false;
        }
        return majorName.equals(year) && isYearLabel(majorName);
    }

    private static boolean isYearLabel(String value) {
        if (value.length() != 4) {
            return false;
        }
        try {
            int year = Integer.parseInt(value);
            return year >= 2000 && year <= 2099;
        }
        catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return "";
        }
        return value.strip();
    }

    static List<Integer> displayYearsFor(int baseYear) {
        return List.of(baseYear + 1, baseYear, baseYear - 1, baseYear - 2);
    }

    /** @deprecated use {@link #displayYearsFor(int)} */
    static List<Integer> companionYearsFor(int baseYear) {
        return displayYearsFor(baseYear);
    }

    public static List<Map<String, String>> expandGroupMajors(
            List<Map<String, String>> currentMajors,
            String universityCode,
            Map<MajorHistoryKey, Map<Integer, Map<String, String>>> historyByMajor) {
        if (currentMajors == null || currentMajors.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> expanded = new ArrayList<>();
        for (Map<String, String> current : currentMajors) {
            if (isHistoryRow(current)) {
                expanded.add(current);
                continue;
            }
            Map<String, String> currentRow = copyCurrentRow(current);
            if (universityCode == null || universityCode.isBlank() || "-".equals(universityCode)) {
                expanded.add(currentRow);
                continue;
            }
            MajorHistoryKey key = MajorHistoryKey.from(universityCode, currentRow);
            Map<Integer, Map<String, String>> historyRows = historyByMajor.get(key);
            if (historyRows == null || historyRows.isEmpty()) {
                expanded.add(currentRow);
                continue;
            }
            int baseYear = parseBaseYear(currentRow);
            Integer baselinePlanCount = parsePlanCount(currentRow.get("plan_count"));
            String baselineCampus = currentRow.get("campus");
            List<YearRow> yearRows = collectYearRows(currentRow, baseYear, historyRows);
            for (int i = 0; i < yearRows.size(); i++) {
                YearRow yearRow = yearRows.get(i);
                if (i == 0) {
                    expanded.add(toAnchorDisplayRow(
                            yearRow,
                            currentRow,
                            baseYear,
                            baselinePlanCount,
                            baselineCampus));
                }
                else {
                    expanded.add(toHistoryDisplayRow(
                            yearRow.fields(),
                            yearRow.year(),
                            baseYear,
                            baselinePlanCount,
                            baselineCampus));
                }
            }
        }
        return expanded;
    }

    private static int parseBaseYear(Map<String, String> row) {
        int year = parseYear(row.get("year"));
        return year > 0 ? year : DefaultAdmissionYear.VALUE;
    }

    private static int parseYear(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.strip());
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    static Integer parsePlanCount(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw.strip());
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, String> copyCurrentRow(Map<String, String> current) {
        Map<String, String> row = new LinkedHashMap<>(current);
        row.putIfAbsent(ROW_KIND, ROW_KIND_CURRENT);
        row.putIfAbsent("row_kind", ROW_KIND_CURRENT);
        return row;
    }

    private record YearRow(int year, Map<String, String> fields) {
    }

    private static List<YearRow> collectYearRows(
            Map<String, String> currentRow,
            int baseYear,
            Map<Integer, Map<String, String>> historyRows) {
        List<YearRow> rows = new ArrayList<>();
        for (int year : displayYearsFor(baseYear)) {
            if (year == baseYear) {
                rows.add(new YearRow(year, currentRow));
            }
            else {
                Map<String, String> history = historyRows.get(year);
                if (history != null && !history.isEmpty()) {
                    rows.add(new YearRow(year, history));
                }
            }
        }
        return rows;
    }

    static Map<String, String> toAnchorDisplayRow(
            YearRow yearRow,
            Map<String, String> currentRow,
            int baseYear,
            Integer baselinePlanCount,
            String baselineCampus) {
        Map<String, String> row;
        if (yearRow.year() == baseYear) {
            row = copyCurrentRow(currentRow);
        }
        else {
            row = toHistoryDisplayRow(yearRow.fields(), yearRow.year(), baseYear, baselinePlanCount, baselineCampus);
            row.put(ROW_KIND, ROW_KIND_CURRENT);
            row.put("row_kind", ROW_KIND_CURRENT);
        }
        row.put("major_name", valueOrDash(currentRow.get("major_name")));
        return row;
    }

    static Map<String, String> toHistoryDisplayRow(
            Map<String, String> history,
            int year,
            int baseYear,
            Integer baselinePlanCount,
            String baselineCampus) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put(ROW_KIND, ROW_KIND_HISTORY);
        row.put("row_kind", ROW_KIND_HISTORY);
        row.put("major_name", "-");
        row.put("plan_count", valueOrDash(history.get("plan_count")));
        row.put("campus", valueOrDash(history.get("campus")));
        row.put("min_score", valueOrDash(history.get("min_score")));
        row.put("min_rank", valueOrDash(history.get("min_rank")));
        row.put("max_score", valueOrDash(history.get("max_score")));
        row.put("year", valueOrDash(history.get("year")));
        row.put("subject_group", valueOrDash(history.get("subject_group")));
        row.put("admission_type", valueOrDash(history.get("admission_type")));
        if (year == baseYear + 1) {
            String planDelta = formatPlanDelta(history.get("plan_count"), baselinePlanCount);
            if (!planDelta.isEmpty()) {
                row.put(PLAN_DELTA, planDelta);
            }
            String campusChange = formatCampusChange(baselineCampus, history.get("campus"));
            if (!campusChange.isEmpty()) {
                row.put(CAMPUS_CHANGE, campusChange);
            }
        }
        return row;
    }

    static String formatCampusChange(String baselineCampus, String futureCampus) {
        String base = normalizeCampusLabel(baselineCampus);
        String future = normalizeCampusLabel(futureCampus);
        if (base.isEmpty() || future.isEmpty() || base.equals(future)) {
            return "";
        }
        return base + "→" + future;
    }

    static String normalizeCampusLabel(String raw) {
        String normalized = normalize(raw);
        if (normalized.isEmpty() || "不分校区".equals(normalized)) {
            return "校本部";
        }
        return normalized;
    }

    static String formatPlanDelta(String companionPlanCount, Integer baselinePlanCount) {
        Integer companion = parsePlanCount(companionPlanCount);
        if (companion == null || baselinePlanCount == null) {
            return "";
        }
        int delta = companion - baselinePlanCount;
        if (delta == 0) {
            return "0";
        }
        return delta > 0 ? "+" + delta : String.valueOf(delta);
    }

    private static String valueOrDash(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value;
    }

    public record MajorHistoryKey(String universityCode, String majorName, String campus, String subjectGroup, String admissionType) {

        static MajorHistoryKey from(String universityCode, Map<String, String> row) {
            return new MajorHistoryKey(
                    universityCode,
                    normalize(row.get("major_name")),
                    normalizeCampus(row.get("campus")),
                    normalize(row.get("subject_group")),
                    normalize(row.get("admission_type")));
        }

        private static String normalize(String value) {
            if (value == null || value.isBlank() || "-".equals(value)) {
                return "";
            }
            return value.strip();
        }

        private static String normalizeCampus(String value) {
            String normalized = normalize(value);
            if ("校本部".equals(normalized) || "不分校区".equals(normalized) || "淮南校区".equals(normalized)) {
                return normalized;
            }
            return normalized;
        }
    }
}
