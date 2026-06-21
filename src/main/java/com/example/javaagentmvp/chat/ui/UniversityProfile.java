package com.example.javaagentmvp.chat.ui;

import java.util.List;

public record UniversityProfile(
        String name,
        String yxdm,
        String province,
        String department,
        String schoolLevel,
        List<String> tags,
        String logoUrl) {
}
