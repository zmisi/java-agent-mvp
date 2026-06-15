package com.example.javaagentmvp.admissionworkflow.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves multi-turn admission intent and slots from the current message plus conversation history.
 */
@Component
public class ConversationTurnResolver {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则");
    private static final Pattern MAJOR_QUERY_HINT = Pattern.compile(
            "专业|报考|报志愿|志愿|可报|能上|录取|哪些|什么专业|报什么|什么学校|哪些学校|院校");
    private static final Pattern SCORE_WITH_FEN = Pattern.compile("(\\d{3,4})\\s*分");
    private static final Pattern YEAR_PATTERN = Pattern.compile("20\\d{2}\\s*年?");
    private static final Pattern SHORT_FOLLOW_UP = Pattern.compile(
            "^(?:那|还有|换成|那么|同样)?\\s*.+(?:呢|怎么样)[？?]?$");

    public ResolvedTurn resolve(
            String currentMessage,
            List<String> priorUserMessagesNewestFirst,
            List<String> priorContextHints) {
        String current = currentMessage == null ? "" : currentMessage.strip();
        if (current.isEmpty()) {
            return ResolvedTurn.unknown("");
        }

        AdmissionInputParser.ParsedAdmissionInput currentParsed = AdmissionInputParser.parse(current);
        AdmissionIntent explicitIntent = classifyExplicitIntent(current);

        if (explicitIntent == AdmissionIntent.POLICY || explicitIntent == AdmissionIntent.REPORT) {
            SlotDelta delta = computeDelta(emptySlots(), currentParsed);
            return new ResolvedTurn(explicitIntent, currentParsed, delta, false);
        }

        ResolvedTurn prior = resolvePriorBaseline(priorUserMessagesNewestFirst, priorContextHints);

        if (explicitIntent != AdmissionIntent.UNKNOWN) {
            AdmissionInputParser.ParsedAdmissionInput merged = mergeSlots(prior.slots(), currentParsed);
            SlotDelta delta = computeDelta(prior.slots(), merged);
            return new ResolvedTurn(explicitIntent, merged, delta, false);
        }

        if ((prior.intent() == AdmissionIntent.RANK || prior.intent() == AdmissionIntent.SCORE)
                && canInheritAsFollowUp(current, currentParsed)) {
            AdmissionInputParser.ParsedAdmissionInput merged = mergeSlots(prior.slots(), currentParsed);
            SlotDelta delta = computeDelta(prior.slots(), merged);
            return new ResolvedTurn(prior.intent(), merged, delta, true);
        }

        return new ResolvedTurn(AdmissionIntent.UNKNOWN, currentParsed, SlotDelta.NONE, false);
    }

    private ResolvedTurn resolvePriorBaseline(
            List<String> priorUserMessagesNewestFirst,
            List<String> priorContextHints) {
        AdmissionIntent intent = AdmissionIntent.UNKNOWN;
        AdmissionInputParser.ParsedAdmissionInput slots = emptySlots();

        List<String> chronological = new ArrayList<>(
                priorUserMessagesNewestFirst == null ? List.of() : priorUserMessagesNewestFirst);
        Collections.reverse(chronological);

        for (String message : chronological) {
            if (message == null || message.isBlank()) {
                continue;
            }
            AdmissionIntent messageIntent = classifyExplicitIntent(message);
            AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(message);
            if (messageIntent == AdmissionIntent.POLICY) {
                intent = AdmissionIntent.POLICY;
            }
            else if (messageIntent != AdmissionIntent.UNKNOWN) {
                intent = messageIntent;
            }
            slots = mergeSlots(slots, parsed);
        }

        if (intent == AdmissionIntent.UNKNOWN) {
            intent = inferIntentFromHints(priorContextHints);
        }

        return new ResolvedTurn(intent, slots, SlotDelta.NONE, false);
    }

    private AdmissionIntent classifyExplicitIntent(String message) {
        String normalized = message.strip();
        if (normalized.isEmpty()) {
            return AdmissionIntent.UNKNOWN;
        }

        boolean hasPolicy = POLICY_PATTERN.matcher(normalized).find();
        boolean hasScore = hasAdmissionScore(normalized);
        boolean hasRank = hasScore && AdmissionRankQuery.isRankQuery(normalized);
        boolean hasMajor = MAJOR_QUERY_HINT.matcher(normalized).find();

        if (hasScore && hasPolicy) {
            return AdmissionIntent.REPORT;
        }
        if (hasPolicy && !hasScore) {
            return AdmissionIntent.POLICY;
        }
        if (hasRank) {
            return AdmissionIntent.RANK;
        }
        if (hasScore && hasMajor) {
            return AdmissionIntent.SCORE;
        }
        if (hasScore && !hasMajor) {
            return AdmissionIntent.UNKNOWN;
        }
        if (hasMajor) {
            return AdmissionIntent.SCORE;
        }
        if (hasPolicy) {
            return AdmissionIntent.POLICY;
        }
        return AdmissionIntent.UNKNOWN;
    }

    private boolean canInheritAsFollowUp(String current, AdmissionInputParser.ParsedAdmissionInput parsed) {
        if (POLICY_PATTERN.matcher(current).find()) {
            return false;
        }
        if (AdmissionRankQuery.isRankQuery(current)) {
            return false;
        }
        if (MAJOR_QUERY_HINT.matcher(current).find()) {
            return false;
        }
        if (hasSlotValue(parsed)) {
            return true;
        }
        return looksLikeShortFollowUp(current);
    }

    private static boolean hasSlotValue(AdmissionInputParser.ParsedAdmissionInput parsed) {
        return parsed.score() != null
                || parsed.province() != null
                || parsed.subjectGroup() != null
                || parsed.year() != null
                || parsed.admissionType() != null;
    }

    private static boolean looksLikeShortFollowUp(String message) {
        String normalized = message.strip();
        if (normalized.isEmpty() || normalized.length() > 40) {
            return false;
        }
        return SHORT_FOLLOW_UP.matcher(normalized).matches();
    }

    private static AdmissionIntent inferIntentFromHints(List<String> hints) {
        if (hints == null || hints.isEmpty()) {
            return AdmissionIntent.UNKNOWN;
        }
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) {
                continue;
            }
            if (assistantRankResultHint(hint)) {
                return AdmissionIntent.RANK;
            }
            if (assistantScoreQueryHint(hint)) {
                return AdmissionIntent.SCORE;
            }
        }
        return AdmissionIntent.UNKNOWN;
    }

    private static boolean assistantRankResultHint(String text) {
        return text.contains("rank-result-table")
                || text.contains("getRankByScore")
                || (text.contains("位次") && text.contains("一分一段"));
    }

    private static boolean assistantScoreQueryHint(String text) {
        return text.contains("getMajorByScore")
                || text.contains("请提供")
                        && (text.contains("省份") || text.contains("科类"));
    }

    private static AdmissionInputParser.ParsedAdmissionInput emptySlots() {
        return new AdmissionInputParser.ParsedAdmissionInput(null, null, null, null, null);
    }

    static AdmissionInputParser.ParsedAdmissionInput mergeSlots(
            AdmissionInputParser.ParsedAdmissionInput base,
            AdmissionInputParser.ParsedAdmissionInput update) {
        return new AdmissionInputParser.ParsedAdmissionInput(
                update.score() != null ? update.score() : base.score(),
                firstNonBlank(update.province(), base.province()),
                firstNonBlank(update.subjectGroup(), base.subjectGroup()),
                update.year() != null ? update.year() : base.year(),
                firstNonBlank(update.admissionType(), base.admissionType()));
    }

    static SlotDelta computeDelta(
            AdmissionInputParser.ParsedAdmissionInput before,
            AdmissionInputParser.ParsedAdmissionInput after) {
        if (after.score() != null && !after.score().equals(before.score())) {
            return SlotDelta.SCORE;
        }
        if (after.province() != null && !after.province().equals(before.province())) {
            return SlotDelta.PROVINCE;
        }
        if (after.subjectGroup() != null && !after.subjectGroup().equals(before.subjectGroup())) {
            return SlotDelta.SUBJECT_GROUP;
        }
        if (after.year() != null && !after.year().equals(before.year())) {
            return SlotDelta.YEAR;
        }
        if (after.admissionType() != null && !after.admissionType().equals(before.admissionType())) {
            return SlotDelta.ADMISSION_TYPE;
        }
        return SlotDelta.NONE;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private static boolean hasAdmissionScore(String normalized) {
        java.util.regex.Matcher fenMatcher = SCORE_WITH_FEN.matcher(normalized);
        while (fenMatcher.find()) {
            if (isPlausibleScore(fenMatcher.group(1))) {
                return true;
            }
        }
        String withoutYears = YEAR_PATTERN.matcher(normalized).replaceAll(" ");
        java.util.regex.Matcher bareMatcher = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)").matcher(withoutYears);
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
