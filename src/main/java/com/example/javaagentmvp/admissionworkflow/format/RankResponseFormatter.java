package com.example.javaagentmvp.admissionworkflow.format;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic HTML for score-to-rank (位次) answers — consistent layout across years.
 */
public final class RankResponseFormatter {

    private RankResponseFormatter() {
    }

    public static String formatIntro(JsonNode rankResult, Integer score, String province) {
        if (rankResult == null || !rankResult.has("ranks") || !rankResult.get("ranks").isArray()) {
            return "未能查询到该分数对应的位次，请补充省份、科类或年份。";
        }
        JsonNode ranks = rankResult.get("ranks");
        if (ranks.isEmpty()) {
            return "未能查询到该分数对应的位次，请补充省份、科类或年份。";
        }

        int queryScore = resolveScore(score, ranks);
        String queryProvince = resolveProvince(province, ranks);
        return formatIntroText(queryScore, queryProvince == null || queryProvince.isBlank()
                ? List.of()
                : List.of(queryProvince));
    }

    public static String formatIntroForProvinces(Integer score, List<String> provinces) {
        if (score == null) {
            return "未能查询到该分数对应的位次，请补充省份、科类或年份。";
        }
        List<String> normalized = provinces == null
                ? List.of()
                : provinces.stream()
                        .filter(p -> p != null && !p.isBlank())
                        .map(String::strip)
                        .distinct()
                        .toList();
        return formatIntroText(score, normalized);
    }

    public static String formatNoRankDataMessage(
            Integer score,
            List<String> regionPhrases,
            List<String> provinces) {
        String target = formatGeographyLabel(regionPhrases, provinces);
        if (target.isBlank()) {
            return "暂未导入相关省份的一分一段表数据，无法查询对应位次。";
        }
        if (score != null) {
            return String.format(
                    Locale.ROOT,
                    "暂未导入%s的一分一段表数据，无法查询%d分对应位次。",
                    target,
                    score);
        }
        return String.format(
                Locale.ROOT,
                "暂未导入%s的一分一段表数据，无法查询对应位次。",
                target);
    }

    private static String formatGeographyLabel(List<String> regionPhrases, List<String> provinces) {
        if (regionPhrases != null && !regionPhrases.isEmpty()) {
            return String.join("、", regionPhrases.stream()
                    .filter(phrase -> phrase != null && !phrase.isBlank())
                    .map(String::strip)
                    .distinct()
                    .toList());
        }
        if (provinces != null && !provinces.isEmpty()) {
            return String.join("、", provinces.stream()
                    .filter(province -> province != null && !province.isBlank())
                    .map(String::strip)
                    .distinct()
                    .toList());
        }
        return "";
    }

    private static String formatIntroText(int queryScore, List<String> provinces) {
        if (provinces.isEmpty()) {
            return String.format(
                    Locale.ROOT,
                    "根据已导入的省级一分一段表，%d分 对应位次如下（均为官方公布数据）：",
                    queryScore);
        }
        if (provinces.size() == 1) {
            return String.format(
                    Locale.ROOT,
                    "根据已导入的省级一分一段表，%s %d分 对应位次如下（均为官方公布数据）：",
                    provinces.get(0),
                    queryScore);
        }
        return String.format(
                Locale.ROOT,
                "根据已导入的省级一分一段表，%s %d分 对应位次如下（均为官方公布数据）：",
                String.join("、", provinces),
                queryScore);
    }

    public static String format(JsonNode rankResult, Integer score, String province) {
        if (rankResult == null || !rankResult.has("ranks") || !rankResult.get("ranks").isArray()) {
            return "未能查询到该分数对应的位次，请补充省份、科类或年份。";
        }
        JsonNode ranks = rankResult.get("ranks");
        if (ranks.isEmpty()) {
            return "未能查询到该分数对应的位次，请补充省份、科类或年份。";
        }

        int queryScore = resolveScore(score, ranks);
        String queryProvince = resolveProvince(province, ranks);

        StringBuilder out = new StringBuilder();
        if (queryProvince != null && !queryProvince.isBlank()) {
            out.append(String.format(
                    Locale.ROOT,
                    "根据已导入的省级一分一段表，**%s** **%d分** 对应位次如下（均为官方公布数据）：%n%n",
                    escapeMarkdown(queryProvince),
                    queryScore));
        }
        else {
            out.append(String.format(
                    Locale.ROOT,
                    "根据已导入的省级一分一段表，**%d分** 对应位次如下（均为官方公布数据）：%n%n",
                    queryScore));
        }

        out.append("<section class=\"rank-table-block\">");
        if (queryProvince != null && !queryProvince.isBlank()) {
            out.append("<div class=\"rank-table-header\">")
                    .append(escapeHtml(queryProvince))
                    .append("</div>");
        }
        out.append("<div class=\"rank-result-wrap\"><table class=\"rank-result-table\">");
        out.append("<thead><tr>");
        appendHeaderCell(out, "年份");
        appendHeaderCell(out, "科类");
        appendHeaderCell(out, "位次区间");
        appendHeaderCell(out, "同分数段人数");
        appendHeaderCell(out, "数据来源");
        out.append("</tr></thead><tbody>");

        for (JsonNode row : sortedRankRows(ranks)) {
            out.append("<tr>");
            appendDataCell(out, formatYearLabel(row, queryScore), true);
            appendDataCell(out, escapeHtml(row.path("subject_group").asText("")), false);
            appendDataCell(out, formatRankRange(row), true);
            appendDataCell(out, formatSegmentCount(row), true);
            appendDataCell(out, formatSourceHtml(row), false);
            out.append("</tr>");
        }

        out.append("</tbody></table></div></section>");
        return out.toString().strip();
    }

    private static void appendHeaderCell(StringBuilder out, String label) {
        out.append("<th scope=\"col\">").append(escapeHtml(label)).append("</th>");
    }

    private static void appendDataCell(StringBuilder out, String text, boolean bold) {
        out.append("<td");
        if (bold) {
            out.append(" class=\"rank-cell-strong\"");
        }
        out.append('>').append(text).append("</td>");
    }

    public static List<JsonNode> sortedRankRows(JsonNode ranks) {
        List<JsonNode> rows = new ArrayList<>();
        ranks.forEach(rows::add);
        rows.sort(Comparator
                .comparingInt((JsonNode r) -> r.path("year").asInt(0)).reversed()
                .thenComparing(r -> r.path("subject_group").asText("")));
        return rows;
    }

    public static String yearLabelPlain(JsonNode row, int score) {
        int year = row.path("year").asInt(0);
        if (year <= 0) {
            return score + "分";
        }
        return year + "年 · " + score + "分";
    }

    public static String rankRangePlain(JsonNode row) {
        if (row.hasNonNull("rank")) {
            return formatNumber(row.path("rank").asInt());
        }
        int min = row.path("rank_min").asInt(-1);
        int max = row.path("rank_max").asInt(-1);
        if (min < 0 || max < 0) {
            return "—";
        }
        if (min == max) {
            return formatNumber(min);
        }
        return formatNumber(min) + "–" + formatNumber(max);
    }

    public static String segmentCountPlain(JsonNode row) {
        if (!row.has("segment_count")) {
            return "—";
        }
        int count = row.path("segment_count").asInt(-1);
        return count >= 0 ? count + "人" : "—";
    }

    public static String sourceLabelPlain(JsonNode row) {
        String url = row.path("source_url").asText("").strip();
        if (!url.isBlank()) {
            return "✅ 官方已公布";
        }
        String provider = row.path("source_provider").asText("").strip();
        if (!provider.isBlank()) {
            return "✅ 官方已公布（" + provider + "）";
        }
        return "✅ 官方已公布";
    }

    private static int resolveScore(Integer score, JsonNode ranks) {
        if (score != null) {
            return score;
        }
        JsonNode first = ranks.get(0);
        if (first != null && first.has("score")) {
            return first.path("score").asInt(0);
        }
        return 0;
    }

    private static String resolveProvince(String province, JsonNode ranks) {
        if (province != null && !province.isBlank()) {
            return province.strip();
        }
        JsonNode first = ranks.get(0);
        if (first != null) {
            return first.path("province").asText("").strip();
        }
        return "";
    }

    private static String formatYearLabel(JsonNode row, int score) {
        return escapeHtml(yearLabelPlain(row, score));
    }

    private static String formatRankRange(JsonNode row) {
        return escapeHtml(rankRangePlain(row));
    }

    private static String formatSegmentCount(JsonNode row) {
        return escapeHtml(segmentCountPlain(row));
    }

    private static String formatSourceHtml(JsonNode row) {
        String url = row.path("source_url").asText("").strip();
        if (!url.isBlank()) {
            return "<span class=\"rank-source\">"
                    + "<span class=\"rank-source-icon\" aria-hidden=\"true\">✅</span>"
                    + "官方已公布 "
                    + "<a class=\"rank-source-link\" href=\""
                    + escapeHtml(url)
                    + "\" target=\"_blank\" rel=\"noopener noreferrer\">来源</a>"
                    + "</span>";
        }
        String provider = row.path("source_provider").asText("").strip();
        if (!provider.isBlank()) {
            return "<span class=\"rank-source\">"
                    + "<span class=\"rank-source-icon\" aria-hidden=\"true\">✅</span>"
                    + "官方已公布（"
                    + escapeHtml(provider)
                    + "）</span>";
        }
        return "<span class=\"rank-source\">"
                + "<span class=\"rank-source-icon\" aria-hidden=\"true\">✅</span>"
                + "官方已公布</span>";
    }

    private static String formatNumber(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String escapeHtml(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeMarkdown(String text) {
        return text.replace("*", "\\*");
    }
}
