package com.example.javaagentmvp.admissionworkflow.intent;

import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AdmissionIntentClassifier {

    private static final Pattern SCORE_WITH_FEN_PATTERN = Pattern.compile("(\\d{3,4})\\s*分");
    private static final Pattern YEAR_PATTERN = Pattern.compile("20\\d{2}\\s*年?");
    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则");

    private final RagQueryRouter ragQueryRouter;
    private final RagProperties ragProperties;

    public AdmissionIntentClassifier(RagQueryRouter ragQueryRouter, RagProperties ragProperties) {
        this.ragQueryRouter = ragQueryRouter;
        this.ragProperties = ragProperties;
    }

    public AdmissionIntent classify(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty()) {
            return AdmissionIntent.UNKNOWN;
        }

        boolean hasScore = hasAdmissionScore(normalized);
        boolean hasRank = hasScore && AdmissionRankQuery.isRankQuery(normalized);
        boolean hasPolicy = POLICY_PATTERN.matcher(normalized).find()
                || matchesRagIntent(normalized);

        if (hasScore && hasPolicy) {
            return AdmissionIntent.REPORT;
        }
        if (hasRank) {
            return AdmissionIntent.RANK;
        }
        if (hasScore) {
            return AdmissionIntent.SCORE;
        }
        if (hasPolicy) {
            return AdmissionIntent.POLICY;
        }

        RagQueryRouter.Decision decision = ragQueryRouter.decide(normalized);
        if (decision.useRag()) {
            return AdmissionIntent.POLICY;
        }
        if (decision.reason() != null && decision.reason().contains("getRankByScore")) {
            return AdmissionIntent.RANK;
        }
        if (decision.reason() != null && decision.reason().contains("getMajorByScore")) {
            return AdmissionIntent.SCORE;
        }
        return AdmissionIntent.UNKNOWN;
    }

    public boolean hasPolicyKeywords(String message) {
        String normalized = message == null ? "" : message.strip();
        return POLICY_PATTERN.matcher(normalized).find() || matchesRagIntent(normalized);
    }

    private boolean matchesRagIntent(String normalized) {
        List<String> keywords = ragProperties.admissions().intentKeywords();
        if (keywords == null) {
            return false;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAdmissionScore(String normalized) {
        Matcher fenMatcher = SCORE_WITH_FEN_PATTERN.matcher(normalized);
        while (fenMatcher.find()) {
            if (isPlausibleScore(fenMatcher.group(1))) {
                return true;
            }
        }
        String withoutYears = YEAR_PATTERN.matcher(normalized).replaceAll(" ");
        Matcher bareMatcher = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)").matcher(withoutYears);
        while (bareMatcher.find()) {
            if (isPlausibleScore(bareMatcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlausibleScore(String raw) {
        try {
            int score = Integer.parseInt(raw);
            return score >= 200 && score <= 750;
        }
        catch (NumberFormatException ex) {
            return false;
        }
    }
}
