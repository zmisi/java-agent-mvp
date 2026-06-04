package com.example.javaagentmvp.chat.ui;

import java.util.List;
import java.util.Map;

public record ChatTable(String title, List<ChatTableColumn> columns, List<Map<String, String>> rows) {
}
