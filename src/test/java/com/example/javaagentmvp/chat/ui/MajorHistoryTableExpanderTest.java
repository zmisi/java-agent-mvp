package com.example.javaagentmvp.chat.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MajorHistoryTableExpanderTest {

    @Test
    void isHistoryRowDetectsYearLabelFallback() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("major_name", "2024");
        row.put("year", "2024");
        assertTrue(MajorHistoryTableExpander.isHistoryRow(row));

        row.put(MajorHistoryTableExpander.ROW_KIND, MajorHistoryTableExpander.ROW_KIND_HISTORY);
        row.put("major_name", "-");
        row.put("year", "2026");
        assertTrue(MajorHistoryTableExpander.isHistoryRow(row));
    }

    @Test
    void displayYearsForOrdersNewestFirst() {
        assertEquals(List.of(2026, 2025, 2024, 2023), MajorHistoryTableExpander.displayYearsFor(2025));
    }

    @Test
    void formatPlanDeltaRelativeToBaseline() {
        assertEquals("+5", MajorHistoryTableExpander.formatPlanDelta("73", 68));
        assertEquals("-3", MajorHistoryTableExpander.formatPlanDelta("65", 68));
        assertEquals("0", MajorHistoryTableExpander.formatPlanDelta("68", 68));
        assertEquals("", MajorHistoryTableExpander.formatPlanDelta("-", 68));
    }

    @Test
    void expandGroupMajorsInsertsHistoryRowsInOrderWithPlanDelta() {
        Map<String, String> current = majorRow("数字媒体技术", "2025");
        MajorHistoryTableExpander.MajorHistoryKey key =
                MajorHistoryTableExpander.MajorHistoryKey.from("AUST", current);
        Map<Integer, Map<String, String>> history = Map.of(
                2024, historyFields("65", "574", "47082", "594", "2024"),
                2023, historyFields("60", "560", "52000", "580", "2023"),
                2026, historyFields("73", "-", "-", "-", "2026"));

        List<Map<String, String>> expanded = MajorHistoryTableExpander.expandGroupMajors(
                List.of(current),
                "AUST",
                Map.of(key, history));

        assertEquals(4, expanded.size());
        assertEquals("数字媒体技术", expanded.get(0).get("major_name"));
        assertEquals("2026", expanded.get(0).get("year"));
        assertEquals("+5", expanded.get(0).get(MajorHistoryTableExpander.PLAN_DELTA));
        assertEquals("-", expanded.get(1).get("major_name"));
        assertEquals("2025", expanded.get(1).get("year"));
        assertFalse(expanded.get(1).containsKey(MajorHistoryTableExpander.PLAN_DELTA));
        assertEquals("-", expanded.get(2).get("major_name"));
        assertEquals("2024", expanded.get(2).get("year"));
        assertEquals("-", expanded.get(3).get("major_name"));
        assertEquals("2023", expanded.get(3).get("year"));
    }

    @Test
    void formatCampusChangeDetectsMoveBetweenCampuses() {
        assertEquals("合肥校区→校本部", MajorHistoryTableExpander.formatCampusChange("合肥校区", "校本部"));
        assertEquals("", MajorHistoryTableExpander.formatCampusChange("校本部", "校本部"));
        assertEquals("", MajorHistoryTableExpander.formatCampusChange("-", "校本部"));
        assertEquals("", MajorHistoryTableExpander.formatCampusChange("校本部", "-"));
        assertEquals("校本部→合肥校区", MajorHistoryTableExpander.formatCampusChange("不分校区", "合肥校区"));
    }

    @Test
    void expandGroupMajorsShows2026PlanWhenCampusMovesFromHefeiToMain() {
        Map<String, String> current = majorRow("机器人工程", "2025");
        current.put("campus", "合肥校区");
        current.put("plan_count", "114");

        MajorHistoryTableExpander.MajorHistoryKey key =
                MajorHistoryTableExpander.MajorHistoryKey.from("AUST", current);
        Map<Integer, Map<String, String>> history = Map.of(
                2024, historyFields("118", "563", "52506", "583", "2024"),
                2023, historyFields("64", "534", "54020", "554", "2023"),
                2026, historyFields("118", "-", "-", "-", "2026", "校本部"));

        List<Map<String, String>> expanded = MajorHistoryTableExpander.expandGroupMajors(
                List.of(current),
                "AUST",
                Map.of(key, history));

        assertEquals(4, expanded.size());
        assertEquals("机器人工程", expanded.get(0).get("major_name"));
        assertEquals("2026", expanded.get(0).get("year"));
        assertEquals("118", expanded.get(0).get("plan_count"));
        assertEquals("校本部", expanded.get(0).get("campus"));
        assertEquals("+4", expanded.get(0).get(MajorHistoryTableExpander.PLAN_DELTA));
        assertEquals("合肥校区→校本部", expanded.get(0).get(MajorHistoryTableExpander.CAMPUS_CHANGE));
        assertEquals("-", expanded.get(1).get("major_name"));
        assertEquals("2025", expanded.get(1).get("year"));
        assertEquals("114", expanded.get(1).get("plan_count"));
        assertEquals("合肥校区", expanded.get(1).get("campus"));
    }

    private static Map<String, String> majorRow(String majorName, String year) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("major_name", majorName);
        row.put("plan_count", "68");
        row.put("campus", "校本部");
        row.put("min_score", "574");
        row.put("min_rank", "47082");
        row.put("max_score", "594");
        row.put("year", year);
        row.put("subject_group", "物理类");
        row.put("admission_type", "普通批");
        return row;
    }

    private static Map<String, String> historyFields(
            String planCount,
            String minScore,
            String minRank,
            String maxScore,
            String year) {
        return historyFields(planCount, minScore, minRank, maxScore, year, "校本部");
    }

    private static Map<String, String> historyFields(
            String planCount,
            String minScore,
            String minRank,
            String maxScore,
            String year,
            String campus) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("plan_count", planCount);
        row.put("campus", campus);
        row.put("min_score", minScore);
        row.put("min_rank", minRank);
        row.put("max_score", maxScore);
        row.put("year", year);
        row.put("subject_group", "物理类");
        row.put("admission_type", "普通批");
        return row;
    }
}
