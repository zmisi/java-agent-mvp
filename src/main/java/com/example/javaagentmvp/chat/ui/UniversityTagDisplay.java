package com.example.javaagentmvp.chat.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class UniversityTagDisplay {

    private static final int MAX_TAGS = 4;

    private UniversityTagDisplay() {
    }

    static List<String> format(List<String> tags, String schoolLevel) {
        Set<String> ordered = new LinkedHashSet<>();
        appendSchoolLevelTags(ordered, schoolLevel);
        if (tags != null) {
            for (String tag : tags) {
                String display = formatTag(tag);
                if (display != null && !display.isBlank()) {
                    ordered.add(display);
                }
            }
        }
        List<String> result = new ArrayList<>(ordered);
        if (result.size() > MAX_TAGS) {
            return List.copyOf(result.subList(0, MAX_TAGS));
        }
        return List.copyOf(result);
    }

    private static void appendSchoolLevelTags(Set<String> ordered, String schoolLevel) {
        if (schoolLevel == null || schoolLevel.isBlank()) {
            return;
        }
        for (String raw : schoolLevel.split("\\s+")) {
            String display = formatTag(raw);
            if (display != null && !display.isBlank()) {
                ordered.add(display);
            }
        }
    }

    private static String formatTag(String raw) {
        if (raw == null) {
            return null;
        }
        String tag = raw.strip();
        if (tag.isEmpty()) {
            return null;
        }
        return switch (tag) {
            case "双一流", "“双一流”建设高校", "\"双一流\"建设高校" -> "“双一流”建设高校";
            case "985" -> "985工程";
            case "211" -> "211工程";
            default -> tag;
        };
    }

    static String formatDepartment(String department) {
        if (department == null || department.isBlank()) {
            return null;
        }
        String value = department.strip();
        if (value.startsWith("主管部门")) {
            return value;
        }
        return "主管部门：" + value;
    }

    static boolean hasUsefulText(String value) {
        return value != null && !value.isBlank() && !"-".equals(value.strip());
    }
}
