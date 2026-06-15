package com.example.javaagentmvp.admissionworkflow.intent;

import java.util.regex.Pattern;

/**
 * Detects score-to-rank (位次/排名) queries that should use MCP {@code getRankByScore},
 * as opposed to score-to-major queries that use {@code getMajorByScore}.
 */
public final class AdmissionRankQuery {

    private static final Pattern RANK_QUERY_HINT =
            Pattern.compile("排名|位次|名次|排多少|多少位|多少名|排第几");
    private static final Pattern MAJOR_QUERY_HINT = Pattern.compile(
            "专业|报考|报志愿|志愿|可报|能上|录取|哪些|什么专业|报什么|什么学校|哪些学校|院校");

    private AdmissionRankQuery() {
    }

    public static boolean isRankQuery(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (!RANK_QUERY_HINT.matcher(message).find()) {
            return false;
        }
        return !MAJOR_QUERY_HINT.matcher(message).find();
    }
}
