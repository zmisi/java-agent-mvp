package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionRankQuery;
import com.example.javaagentmvp.admissionworkflow.intent.SlotDelta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Multi-turn intent inheritance and slot-delta rules used by {@link LocalAdmissionQueryCompiler}.
 */
public final class MultiturnIntentSupport {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则");
    private static final Pattern MAJOR_QUERY_HINT = Pattern.compile(
            "专业|报考|报志愿|志愿|可报|能上|录取|哪些|什么专业|报什么|什么学校|哪些学校|院校");
    private static final Pattern SCORE_WITH_FEN = Pattern.compile("(\\d{3,4})\\s*分");
    private static final Pattern YEAR_PATTERN = Pattern.compile("20\\d{2}\\s*年?");
    private static final Pattern SHORT_FOLLOW_UP = Pattern.compile(
            "^(?:那|还有|换成|那么|同样)?\\s*.+(?:呢|怎么样)[？?]?$");
    private static final Pattern GEOGRAPHY_SHORT = Pattern.compile("^在.+$");

    private MultiturnIntentSupport() {
    }

    /** Whether the current user message alone carries admission-related signals (slots, filters, intent phrases). */
    public static boolean isCurrentTurnAdmissionRelated(
            String rawMessage,
            AdmissionInputParser.ParsedAdmissionInput parsed,
            AdmissionQueryIr currentTurn) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        AdmissionIntent explicit = classifyExplicitIntent(rawMessage);
        if (explicit == AdmissionIntent.POLICY || explicit == AdmissionIntent.REPORT) {
            return true;
        }
        if (explicit != AdmissionIntent.UNKNOWN) {
            return true;
        }
        if (AdmissionRankQuery.isRankQuery(rawMessage)) {
            return true;
        }
        if (hasAdmissionRefinement(currentTurn)) {
            return true;
        }
        if (hasSlotValue(parsed)) {
            return true;
        }
        if (canInheritAsFollowUp(rawMessage, parsed)) {
            return true;
        }
        if (POLICY_PATTERN.matcher(rawMessage).find()) {
            return true;
        }
        return MAJOR_QUERY_HINT.matcher(rawMessage).find();
    }
    public static boolean hasAdmissionRefinement(AdmissionQueryIr current) {
        if (current == null) {
            return false;
        }
        AdmissionFiltersIr filters = current.filters();
        if (filters != null) {
            if (!filters.excludeSchoolNameContains().isEmpty()
                    || !filters.excludeMajorKeywords().isEmpty()
                    || !filters.includeMajorKeywords().isEmpty()
                    || !filters.includeSchools().isEmpty()
                    || filters.hasMajorCategoryFilter()) {
                return true;
            }
        }
        if (current.regions() != null && !current.regions().isEmpty()) {
            return true;
        }
        if (current.preferences() != null && !current.preferences().isEmpty()) {
            return true;
        }
        return current.hasUnsupportedConstraints();
    }

    public static AdmissionIntent classifyExplicitIntent(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty()) {
            return AdmissionIntent.UNKNOWN;
        }

        boolean hasPolicy = POLICY_PATTERN.matcher(normalized).find();
        boolean hasScore = hasAdmissionScore(normalized);
        boolean hasMajor = MAJOR_QUERY_HINT.matcher(normalized).find();

        if (hasScore && hasPolicy) {
            return AdmissionIntent.REPORT;
        }
        if (hasPolicy && !hasScore) {
            return AdmissionIntent.POLICY;
        }
        if (AdmissionRankQuery.isRankQuery(normalized)) {
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

    public static AdmissionIntent inferPriorIntent(List<String> priorUserMessagesNewestFirst) {
        if (priorUserMessagesNewestFirst == null || priorUserMessagesNewestFirst.isEmpty()) {
            return AdmissionIntent.UNKNOWN;
        }
        List<String> chronological = new ArrayList<>(priorUserMessagesNewestFirst);
        Collections.reverse(chronological);

        AdmissionIntent intent = AdmissionIntent.UNKNOWN;
        for (String message : chronological) {
            if (message == null || message.isBlank()) {
                continue;
            }
            AdmissionIntent messageIntent = classifyExplicitIntent(message);
            if (messageIntent == AdmissionIntent.POLICY) {
                intent = AdmissionIntent.POLICY;
            }
            else if (messageIntent != AdmissionIntent.UNKNOWN) {
                intent = messageIntent;
            }
        }
        return intent;
    }

    /** When prior turns only established score slots without an explicit task phrase, infer search intent. */
    public static AdmissionIntent resolvePriorSearchIntent(
            List<String> priorUserMessagesNewestFirst,
            AdmissionSlotsIr priorSlots) {
        AdmissionIntent intent = inferPriorIntent(priorUserMessagesNewestFirst);
        if (intent == AdmissionIntent.POLICY || intent == AdmissionIntent.REPORT) {
            return intent;
        }
        if (intent == AdmissionIntent.RANK || intent == AdmissionIntent.SCORE) {
            return intent;
        }
        if (priorSlots == null || !priorSlots.hasScoreOrRank()) {
            return AdmissionIntent.UNKNOWN;
        }
        List<String> chronological = new ArrayList<>(priorUserMessagesNewestFirst);
        Collections.reverse(chronological);
        for (String message : chronological) {
            if (message != null && AdmissionRankQuery.isRankQuery(message)) {
                return AdmissionIntent.RANK;
            }
        }
        return AdmissionIntent.SCORE;
    }

    public static boolean canInheritAsFollowUp(String current, AdmissionInputParser.ParsedAdmissionInput parsed) {
        if (current == null || current.isBlank()) {
            return false;
        }
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
        if (looksLikeGeographyShort(current)) {
            return true;
        }
        return looksLikeShortFollowUp(current);
    }

    public static SlotDelta computeSlotDelta(AdmissionSlotsIr before, AdmissionSlotsIr after) {
        if (before == null || after == null) {
            return SlotDelta.NONE;
        }
        if (after.rank() != null && !after.rank().equals(before.rank())) {
            return SlotDelta.SCORE;
        }
        if (after.score() != null && !after.score().equals(before.score())) {
            return SlotDelta.SCORE;
        }
        String afterProvince = after.primaryProvince();
        String beforeProvince = before.primaryProvince();
        if (afterProvince != null && !afterProvince.equals(beforeProvince)) {
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

    private static boolean hasSlotValue(AdmissionInputParser.ParsedAdmissionInput parsed) {
        if (parsed == null) {
            return false;
        }
        return parsed.score() != null
                || parsed.rank() != null
                || parsed.province() != null
                || parsed.subjectGroup() != null
                || parsed.year() != null
                || parsed.admissionType() != null;
    }

    private static boolean looksLikeGeographyShort(String message) {
        String normalized = message.strip();
        return !normalized.isEmpty() && GEOGRAPHY_SHORT.matcher(normalized).matches();
    }

    private static boolean looksLikeShortFollowUp(String message) {
        String normalized = message.strip();
        if (normalized.isEmpty() || normalized.length() > 40) {
            return false;
        }
        return SHORT_FOLLOW_UP.matcher(normalized).matches();
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
