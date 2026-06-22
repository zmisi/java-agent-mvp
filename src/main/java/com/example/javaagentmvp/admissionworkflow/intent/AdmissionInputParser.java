package com.example.javaagentmvp.admissionworkflow.intent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private static final String RANK_NUMBER = "(\\d+(?:,\\d+)*\\s*万|\\d+(?:,\\d+)*)";
    private static final Pattern RANK_WITH_MING_PATTERN = Pattern.compile("排名\\s*" + RANK_NUMBER + "\\s*名");
    private static final Pattern RANK_DEDI_PATTERN = Pattern.compile("第\\s*" + RANK_NUMBER + "\\s*名");
    private static final Pattern RANK_WEICI_PATTERN = Pattern.compile("位次\\s*" + RANK_NUMBER);
    private static final Pattern RANK_WEI_PATTERN = Pattern.compile(RANK_NUMBER + "\\s*位(?!次)");
    private static final Pattern RANK_MING_PATTERN = Pattern.compile(RANK_NUMBER + "\\s*名");
    private static final Pattern RANK_HINT_PATTERN =
            Pattern.compile("排名|位次|名次|排多少|多少位|多少名|排第几");
    private static final Pattern PROVINCE_PATTERN = Pattern.compile(
            "(安徽|北京|上海|天津|重庆|江苏|浙江|山东|河南|河北|湖北|湖南|广东|四川|陕西|福建|江西|山西|辽宁|吉林|黑龙江|内蒙古|广西|云南|贵州|甘肃|海南|宁夏|青海|西藏|新疆)");
    private static final Pattern SUBJECT_PATTERN = Pattern.compile("(物理类?|历史类?|物理组|历史组|物理|历史)");
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})\\s*年?");
    private static final Pattern ADMISSION_TYPE_PATTERN =
            Pattern.compile("(普通批|国家专项|地方专项|提前批|本科提前批|专科批)");

    private static final List<String> INCLUDE_MAJOR_KEYWORDS = List.of(
            "计算机科学与技术",
            "计算机",
            "软件工程",
            "人工智能",
            "信息安全",
            "物联网工程");

    private AdmissionInputParser() {
    }

    public record ParsedAdmissionInput(
            Integer score,
            Integer rank,
            String province,
            String subjectGroup,
            Integer year,
            String admissionType) {
    }

    public static ParsedAdmissionInput parse(String message) {
        String normalized = message == null ? "" : message.strip();
        return new ParsedAdmissionInput(
                parseScore(normalized).orElse(null),
                parseRank(normalized).orElse(null),
                parseProvince(normalized).orElse(null),
                parseSubjectGroup(normalized).orElse(null),
                parseYear(normalized).orElse(null),
                parseAdmissionType(normalized).orElse(null));
    }

    public static Optional<Integer> parseRank(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        Matcher rankWithMing = RANK_WITH_MING_PATTERN.matcher(message);
        if (rankWithMing.find()) {
            return Optional.of(parseRankNumber(rankWithMing.group(1)));
        }
        Matcher rankDedi = RANK_DEDI_PATTERN.matcher(message);
        if (rankDedi.find()) {
            return Optional.of(parseRankNumber(rankDedi.group(1)));
        }
        Matcher rankWeici = RANK_WEICI_PATTERN.matcher(message);
        if (rankWeici.find()) {
            return Optional.of(parseRankNumber(rankWeici.group(1)));
        }
        Matcher rankWei = RANK_WEI_PATTERN.matcher(message);
        if (rankWei.find()) {
            return Optional.of(parseRankNumber(rankWei.group(1)));
        }
        Matcher rankMing = RANK_MING_PATTERN.matcher(message);
        if (rankMing.find()) {
            return Optional.of(parseRankNumber(rankMing.group(1)));
        }

        boolean hasRankHint = RANK_HINT_PATTERN.matcher(message).find();
        Set<Integer> yearValues = collectYearValues(message);
        Matcher matcher = BARE_NUMBER_PATTERN.matcher(message);
        Integer lastPlausibleRank = null;
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (yearValues.contains(value) || isPlausibleAdmissionScore(value)) {
                continue;
            }
            lastPlausibleRank = value;
        }
        if (lastPlausibleRank != null && hasRankHint) {
            return Optional.of(lastPlausibleRank);
        }
        return Optional.empty();
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

    public static List<String> parseIncludeMajorKeywords(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        INCLUDE_MAJOR_KEYWORDS.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .forEach(keyword -> {
                    if (message.contains(keyword) && !matched.contains(keyword)) {
                        matched.add(keyword);
                    }
                });
        if (matched.contains("计算机科学与技术") && matched.contains("计算机")) {
            matched.remove("计算机");
        }
        return List.copyOf(matched);
    }

    private static int parseRankNumber(String text) {
        String normalized = text.strip().replace(" ", "");
        if (normalized.endsWith("万")) {
            String numPart = normalized.substring(0, normalized.length() - 1).replace(",", "");
            long rank = Math.round(Double.parseDouble(numPart) * 10_000L);
            if (rank < 1 || rank > Integer.MAX_VALUE) {
                throw new NumberFormatException("rank out of range: " + text);
            }
            return (int) rank;
        }
        return Integer.parseInt(normalized.replace(",", ""));
    }

    private static int parsePositiveInt(String text) {
        return Integer.parseInt(text.strip());
    }

    public static String describeMissingFields(ParsedAdmissionInput parsed) {
        if (parsed.score() == null && parsed.rank() == null) {
            return "score";
        }
        if (parsed.province() == null || parsed.province().isBlank()) {
            return "province";
        }
        return null;
    }
}
