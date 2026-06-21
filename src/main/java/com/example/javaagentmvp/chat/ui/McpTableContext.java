package com.example.javaagentmvp.chat.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Correlates MCP tool tables for a single chat request. */
public final class McpTableContext {

    private static final ThreadLocal<List<ChatTable>> TABLES = ThreadLocal.withInitial(ArrayList::new);

    private McpTableContext() {
    }

    public static void add(ChatTable table) {
        if (table == null) {
            return;
        }
        TABLES.get().add(table);
    }

    public static List<ChatTable> tables() {
        List<ChatTable> tables = TABLES.get();
        return tables.isEmpty() ? List.of() : List.copyOf(tables);
    }

    public static void clear() {
        TABLES.remove();
    }

    /** Removes the last {@code count} tables (e.g. rank-tier tables before score-tier fallback). */
    public static void removeLastTables(int count) {
        if (count <= 0) {
            return;
        }
        List<ChatTable> tables = TABLES.get();
        for (int i = 0; i < count && !tables.isEmpty(); i++) {
            tables.remove(tables.size() - 1);
        }
    }
}
