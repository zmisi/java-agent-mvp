package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatTable(
        String title,
        List<ChatTableColumn> columns,
        List<Map<String, String>> rows,
        List<ChatTableGroup> groups,
        String province) {

    public ChatTable(String title, List<ChatTableColumn> columns, List<Map<String, String>> rows) {
        this(title, columns, rows, List.of(), null);
    }

    public ChatTable(String title, List<ChatTableColumn> columns, List<Map<String, String>> rows, String province) {
        this(title, columns, rows, List.of(), province);
    }

    public ChatTable withProvince(String nextProvince) {
        if (nextProvince == null || nextProvince.isBlank()) {
            return this;
        }
        String label = nextProvince.strip();
        return new ChatTable(label, columns, rows, groups, label);
    }
}
