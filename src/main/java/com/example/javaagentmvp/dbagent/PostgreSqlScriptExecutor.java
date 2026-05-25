package com.example.javaagentmvp.dbagent;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class PostgreSqlScriptExecutor {

    private PostgreSqlScriptExecutor() {
    }

    static void execute(Connection connection, String script) throws SQLException {
        for (String statement : splitStatements(script)) {
            String sql = statement.strip();
            if (sql.isEmpty()) {
                continue;
            }
            try (Statement statementHandle = connection.createStatement()) {
                statementHandle.execute(sql);
            }
        }
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        String dollarTag = null;

        int index = 0;
        while (index < script.length()) {
            char character = script.charAt(index);

            if (dollarTag != null) {
                if (character == '$' && matchesDollarQuoteDelimiter(script, index, dollarTag)) {
                    int end = index + dollarTag.length() + 2;
                    current.append(script, index, end);
                    index = end;
                    dollarTag = null;
                    continue;
                }
                current.append(character);
                index++;
                continue;
            }

            if (inSingleQuote) {
                current.append(character);
                if (character == '\'' && index + 1 < script.length() && script.charAt(index + 1) == '\'') {
                    current.append(script.charAt(index + 1));
                    index += 2;
                    continue;
                }
                if (character == '\'') {
                    inSingleQuote = false;
                }
                index++;
                continue;
            }

            if (character == '\'') {
                inSingleQuote = true;
                current.append(character);
                index++;
                continue;
            }

            if (character == '$') {
                String tag = parseDollarTag(script, index);
                if (tag != null) {
                    int end = index + tag.length() + 2;
                    current.append(script, index, end);
                    dollarTag = tag;
                    index = end;
                    continue;
                }
            }

            if (character == ';') {
                statements.add(current.toString());
                current = new StringBuilder();
                index++;
                continue;
            }

            current.append(character);
            index++;
        }

        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    private static String parseDollarTag(String script, int start) {
        int second = script.indexOf('$', start + 1);
        if (second < 0) {
            return null;
        }
        String tag = script.substring(start + 1, second);
        if (!tag.matches("[A-Za-z0-9_]*")) {
            return null;
        }
        return tag;
    }

    private static boolean matchesDollarQuoteDelimiter(String script, int start, String tag) {
        if (script.charAt(start) != '$') {
            return false;
        }
        int tagStart = start + 1;
        int tagEnd = tagStart + tag.length();
        if (tagEnd >= script.length()) {
            return false;
        }
        if (!script.regionMatches(tagStart, tag, 0, tag.length())) {
            return false;
        }
        return tagEnd < script.length() && script.charAt(tagEnd) == '$';
    }
}
