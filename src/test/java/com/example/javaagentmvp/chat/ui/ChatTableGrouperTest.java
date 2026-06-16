package com.example.javaagentmvp.chat.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTableGrouperTest {

    @Test
    void groupsRowsByUniversityAndSortsMajorsByMinScoreDesc() {
        List<Map<String, String>> rows = List.of(
                row("AHU", "安徽大学", "法学", "615"),
                row("AHU", "安徽大学", "汉语言文学", "614"),
                row("HFUT", "合肥工业大学", "软件工程", "628"),
                row("HFUT", "合肥工业大学", "土木工程", "620"));

        List<ChatTableGroup> groups = ChatTableGrouper.groupMajorRows(rows);

        assertEquals(2, groups.size());
        assertEquals("合肥工业大学", groups.get(0).universityName());
        assertEquals("HFUT", groups.get(0).universityCode());
        assertEquals(2, groups.get(0).majorCount());
        assertEquals("620", groups.get(0).minScore());
        assertEquals("软件工程", groups.get(0).majors().get(0).get("major_name"));
        assertEquals("土木工程", groups.get(0).majors().get(1).get("major_name"));

        assertEquals("安徽大学", groups.get(1).universityName());
        assertEquals(2, groups.get(1).majorCount());
        assertEquals("614", groups.get(1).minScore());
        assertFalse(groups.get(1).majors().get(0).containsKey("university_name"));
        assertEquals("法学", groups.get(1).majors().get(0).get("major_name"));
    }

    @Test
    void withGroupsAddsGroupsToTableWithoutMutatingExistingGroups() {
        List<Map<String, String>> rows = List.of(row("AHU", "安徽大学", "法学", "615"));
        ChatTable grouped = ChatTableGrouper.withGroups(new ChatTable("冲", List.of(), rows));

        assertEquals(1, grouped.groups().size());
        assertEquals("安徽大学", grouped.groups().get(0).universityName());

        ChatTable again = ChatTableGrouper.withGroups(grouped);
        assertEquals(grouped, again);
    }

    @Test
    void enrichTablesBackfillsLegacyTablesWithoutGroups() {
        List<Map<String, String>> rows = List.of(
                row("HFUU", "合肥大学", "计算机科学与技术", "553"),
                row("HFUU", "合肥大学", "英语", "535"));
        ChatTable legacy = new ChatTable("保", List.of(), rows);

        List<ChatTable> enriched = ChatTableGrouper.enrichTables(List.of(legacy));

        assertEquals(1, enriched.size());
        assertEquals(1, enriched.get(0).groups().size());
        assertEquals(2, enriched.get(0).groups().get(0).majorCount());
        assertEquals("535", enriched.get(0).groups().get(0).minScore());
    }

    @Test
    void withGroupsSkipsRankTables() {
        List<ChatTableColumn> columns = List.of(
                new ChatTableColumn("year_label", "年份"),
                new ChatTableColumn("subject_group", "科类"),
                new ChatTableColumn("rank_range", "位次区间"),
                new ChatTableColumn("segment_count", "同分数段人数"),
                new ChatTableColumn("source_label", "数据来源"));
        Map<String, String> rankRow = new LinkedHashMap<>();
        rankRow.put("year_label", "2025年 · 630分");
        rankRow.put("subject_group", "历史类");
        rankRow.put("rank_range", "714–763");
        rankRow.put("segment_count", "50人");
        rankRow.put("source_label", "✅ 官方已公布");

        ChatTable rankTable = ChatTableGrouper.withGroups(new ChatTable("", columns, List.of(rankRow)));

        assertTrue(rankTable.groups().isEmpty());
        assertEquals("714–763", rankTable.rows().get(0).get("rank_range"));
    }

    @Test
    void emptyRowsProduceEmptyGroups() {
        assertTrue(ChatTableGrouper.groupMajorRows(List.of()).isEmpty());
        ChatTable table = ChatTableGrouper.withGroups(new ChatTable("冲", List.of(), List.of()));
        assertTrue(table.groups().isEmpty());
    }

    private static Map<String, String> row(String code, String university, String major, String minScore) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("university_code", code);
        row.put("university_name", university);
        row.put("major_name", major);
        row.put("campus", "合肥校区");
        row.put("min_score", minScore);
        row.put("min_rank", "10000");
        row.put("max_score", "630");
        row.put("year", "2025");
        row.put("subject_group", "物理类");
        row.put("admission_type", "普通批");
        return row;
    }
}
