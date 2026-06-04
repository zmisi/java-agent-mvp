package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

public final class UiTableCodec {

    private static final TypeReference<List<ChatTable>> TABLE_LIST_TYPE = new TypeReference<>() {
    };

    private UiTableCodec() {
    }

    public static List<ChatTable> readUiTables(JsonNode node, ObjectMapper objectMapper) {
        JsonNode uiTablesNode = node.get("uiTables");
        if (uiTablesNode == null || !uiTablesNode.isArray() || uiTablesNode.isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(uiTablesNode, TABLE_LIST_TYPE);
        }
        catch (IllegalArgumentException ex) {
            return List.of();
        }
    }
}
