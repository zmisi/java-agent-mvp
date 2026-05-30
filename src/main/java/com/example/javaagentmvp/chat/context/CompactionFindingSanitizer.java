package com.example.javaagentmvp.chat.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

final class CompactionFindingSanitizer {

    static final int MAX_LABEL_CHARS = 60;
    private static final Pattern MARKDOWN_OR_NOISE = Pattern.compile(
            "[`*#]|---|###|✅|❌|资料来源|rag-docs|```", Pattern.CASE_INSENSITIVE);

    private CompactionFindingSanitizer() {
    }

    static List<String> sanitizeList(List<String> rawFindings) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : rawFindings) {
            for (String piece : splitRawFinding(raw)) {
                String label = sanitize(piece);
                if (isAcceptable(label)) {
                    out.add(label);
                }
            }
            if (out.size() >= 8) {
                break;
            }
        }
        return List.copyOf(out);
    }

    static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = CompactionMessageUtils.oneLine(raw)
                .replace("**", "")
                .replace("`", "")
                .replace("✅", "")
                .replace("❌", "")
                .strip();
        int period = findFirstSentenceEnd(text);
        if (period > 8 && period <= MAX_LABEL_CHARS) {
            text = text.substring(0, period).strip();
        }
        if (text.length() > MAX_LABEL_CHARS) {
            text = text.substring(0, MAX_LABEL_CHARS).strip() + "…";
        }
        return text;
    }

    static boolean isAcceptable(String label) {
        if (label == null || label.isBlank()) {
            return false;
        }
        if (label.length() < 4 || label.length() > MAX_LABEL_CHARS + 1) {
            return false;
        }
        if (MARKDOWN_OR_NOISE.matcher(label).find()) {
            return false;
        }
        String n = label.strip();
        return !(n.length() < 8
                || n.equals("好的，我来帮你查一下")
                || (n.startsWith("好的") && n.length() < 16));
    }

    private static List<String> splitRawFinding(String raw) {
        List<String> parts = new ArrayList<>();
        for (String segment : raw.split("\\|")) {
            String piece = segment.strip();
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.contains("\n")) {
                for (String line : piece.split("\\n")) {
                    String linePiece = line.strip().replaceFirst("^[-*]\\s*", "");
                    if (!linePiece.isEmpty()) {
                        parts.add(linePiece);
                    }
                }
            } else {
                parts.add(piece);
            }
        }
        return parts;
    }

    private static int findFirstSentenceEnd(String text) {
        int cn = text.indexOf('。');
        int en = text.indexOf('.');
        if (cn < 0) {
            return en;
        }
        if (en < 0) {
            return cn;
        }
        return Math.min(cn, en);
    }
}
