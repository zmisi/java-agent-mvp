package com.example.javaagentmvp.admissionworkflow.intent;

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdmissionInputParser {

    private static final int MIN_ADMISSION_SCORE = 200;
    private static final int MAX_ADMISSION_SCORE = 750;

    private static final Pattern SCORE_WITH_FEN_PATTERN = Pattern.compile("(?<!\\d)(\\d{3,4})\\s*分");
    private static final Pattern BARE_NUMBER_PATTERN = Pattern.compile("(?<!\\d)(\\d{3,4})(?!\\d)");
    private static final Pattern PROVINCE_PATTERN = Pattern.compile(
            "(安徽|北京|上海|天津|重庆|江苏|浙江|山东|河南|河北|湖北|湖南|广东|四川|陕西|福建|江西|山西|辽宁|吉林|黑龙江|内蒙古|广西|云南|贵州|甘肃|海南|宁夏|青海|西藏|新疆)");
    private static final Pattern SUBJECT_PATTERN = Pattern.compile("(物理类?|历史类?|物理组|历史组|物理|历史)");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})\\s*年?");
    private static final Pattern ADMISSION_TYPE_PATTERN =
            Pattern.compile("(普通批|国家专项|地方专项|提前批|本科提前批|专科批)");

    private AdmissionInputParser() {
    }

    public record ParsedAdmissionInput(
            Integer score,
            String province,
            String subjectGroup,
            Integer year,
            String admissionType) {
    }

    public static ParsedAdmissionInput parse(String message) {
        String normalized = message == null ? "" : message.strip();
        return new ParsedAdmissionInput(
                parseScore(normalized).orElse(null),
                parseProvince(normalized).orElse(null),
                parseSubjectGroup(normalized).orElse(null),
                parseYear(normalized).orElse(null),
                parseAdmissionType(normalized).orElse(null));
    }

    public static Optional<Integer> parseScore(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        Matcher fenMatcher = SCORE_WITH_FEN_PATTERN.matcher(message);
        if (fenMatcher.find()) {
            int explicit = Integer.parseInt(fenMatcher.group(1));
            if (isPlausibleAdmissionScore(explicit)) {
                return Optional.of(explicit);
            }
        }

        Set<Integer> yearValues = collectYearValues(message);
        Matcher matcher = BARE_NUMBER_PATTERN.matcher(message);
        Integer lastPlausible = null;
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (yearValues.contains(value) || !isPlausibleAdmissionScore(value)) {
                continue;
            }
            lastPlausible = value;
        }
        return Optional.ofNullable(lastPlausible);
    }

    private static Set<Integer> collectYearValues(String message) {
        Set<Integer> years = new HashSet<>();
        Matcher yearMatcher = YEAR_PATTERN.matcher(message);
        while (yearMatcher.find()) {
            years.add(Integer.parseInt(yearMatcher.group(1)));
        }
        return years;
    }

    private static boolean isPlausibleAdmissionScore(int value) {
        return value >= MIN_ADMISSION_SCORE && value <= MAX_ADMISSION_SCORE;
    }

    public static Optional<String> parseProvince(String message) {
        Matcher matcher = PROVINCE_PATTERN.matcher(message);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    public static Optional<String> parseSubjectGroup(String message) {
        Matcher matcher = SUBJECT_PATTERN.matcher(message);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (value.startsWith("物理")) {
                return Optional.of("物理类");
            }
            if (value.startsWith("历史")) {
                return Optional.of("历史类");
            }
            return Optional.of(value);
        }
        return Optional.empty();
    }

    public static Optional<Integer> parseYear(String message) {
        Matcher matcher = YEAR_PATTERN.matcher(message);
        if (matcher.find()) {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        }
        return Optional.empty();
    }

    public static Optional<String> parseAdmissionType(String message) {
        Matcher matcher = ADMISSION_TYPE_PATTERN.matcher(message);
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    public static String describeMissingFields(ParsedAdmissionInput parsed) {
        if (parsed.score() == null) {
            return "score";
        }
        if (parsed.province() == null || parsed.province().isBlank()) {
            return "province";
        }
        return null;
    }
}
