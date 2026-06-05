package com.example.javaagentmvp.chat.ui;

import java.util.List;
import java.util.Map;

public record ChatTableGroup(
        String universityCode,
        String universityName,
        int majorCount,
        String minScore,
        List<Map<String, String>> majors) {
}
