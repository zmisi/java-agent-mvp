package com.example.javaagentmvp.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
public class RagQueryRouter {

    private static final Logger log = LoggerFactory.getLogger(RagQueryRouter.class);

    private final RagProperties ragProperties;

    private final List<Pattern> ragPatterns;

    private final List<Pattern> databasePatterns;

    public RagQueryRouter(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.ragPatterns = compilePatterns(ragProperties.routing().ragPatterns(), "rag-patterns");
        this.databasePatterns = compilePatterns(ragProperties.routing().databasePatterns(), "database-patterns");
        log.info("RAG routing loaded: {} rag pattern(s), {} database pattern(s)",
                ragPatterns.size(), databasePatterns.size());
    }

    public Decision decide(String message) {
        return decide(message, List.of(), List.of());
    }

    public Decision decide(String message, List<String> priorUserMessages, List<String> priorContextHints) {
        if (!ragProperties.enabled()) {
            return Decision.skip("RAG disabled");
        }
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty()) {
            return Decision.skip("empty message");
        }

        if (ragProperties.routeDatabaseQueries() && matchesPatterns(normalized, databasePatterns)) {
            return Decision.skip("structured query — use MCP tools (SQL or getMajorByScore)");
        }

        if (isScoreQueryFollowUp(normalized, priorUserMessages, priorContextHints)) {
            return Decision.skip("score query follow-up — use MCP getMajorByScore");
        }

        if (matchesPatterns(normalized, ragPatterns)) {
            return Decision.use("matched RAG/doc intent");
        }

        return Decision.retrieve("ambiguous — retrieve then score");
    }

    /**
     * User is supplying province / subject / year / batch after a prior score-to-major question
     * (or after the assistant asked for those fields). Route to MCP, not RAG.
     */
    private boolean isScoreQueryFollowUp(
            String current,
            List<String> priorUserMessages,
            List<String> priorContextHints) {
        if (matchesPatterns(current, ragPatterns)) {
            return false;
        }
        if (!hasPriorScoreQueryIntent(priorUserMessages, priorContextHints)) {
            return false;
        }
        return looksLikeAdmissionParameters(current);
    }

    private boolean hasPriorScoreQueryIntent(List<String> priorUserMessages, List<String> priorContextHints) {
        if (priorUserMessages != null) {
            for (String prior : priorUserMessages) {
                if (prior != null && isScoreMajorQuery(prior)) {
                    return true;
                }
            }
        }
        if (priorContextHints != null) {
            for (String hint : priorContextHints) {
                if (hint != null && assistantAskingForScoreParams(hint)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isScoreMajorQuery(String message) {
        String normalized = message.strip();
        if (!SCORE_PATTERN.matcher(normalized).find()) {
            return false;
        }
        return MAJOR_QUERY_HINT.matcher(normalized).find();
    }

    private static boolean assistantAskingForScoreParams(String text) {
        return ASSISTANT_ASKING_PARAMS.matcher(text).find();
    }

    private static boolean looksLikeAdmissionParameters(String message) {
        String normalized = message.strip();
        boolean hasSubjectOrBatch = ADMISSION_PARAMS.matcher(normalized).find();
        boolean hasProvince = PROVINCE_PATTERN.matcher(normalized).find();
        boolean hasYear = YEAR_PATTERN.matcher(normalized).find();
        return hasSubjectOrBatch || (hasProvince && (hasYear || hasSubjectOrBatch))
                || (hasProvince && normalized.length() <= 40);
    }

    private static final Pattern SCORE_PATTERN = Pattern.compile("\\d{3,4}\\s*分");
    private static final Pattern MAJOR_QUERY_HINT = Pattern.compile(
            "专业|报考|报志愿|志愿|可报|能上|录取|哪些|什么专业|报什么|什么学校|哪些学校|院校");
    private static final Pattern ADMISSION_PARAMS = Pattern.compile(
            "物理类|历史类|普通批|国家专项|地方专项|中外合作");
    private static final Pattern ASSISTANT_ASKING_PARAMS = Pattern.compile(
            "省份|科类|物理类|历史类|所在省|提供.*省|getMajorByScore");
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b20\\d{2}\\b");
    private static final Pattern PROVINCE_PATTERN = Pattern.compile(
            "安徽|北京|上海|天津|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|福建|江西|山东|河南|湖北|湖南|广东|海南|四川|贵州|云南|陕西|甘肃|青海|内蒙古|广西|西藏|宁夏|新疆");

    public Decision afterRetrieval(Decision preliminary, List<Document> documents) {
        if (!preliminary.shouldRetrieve()) {
            return preliminary;
        }
        if (documents.isEmpty()) {
            return Decision.skip("no matching document chunks");
        }
        double bestDistance = bestDistance(documents);
        if (bestDistance > ragProperties.maxDistance()) {
            return Decision.skip("weak retrieval (best distance="
                    + formatDistance(bestDistance) + " > maxDistance=" + ragProperties.maxDistance() + ")");
        }
        return Decision.use("retrieval score ok (best distance=" + formatDistance(bestDistance) + ")");
    }

    private static boolean matchesPatterns(String message, List<Pattern> patterns) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (Pattern pattern : patterns) {
            if (pattern.matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> compilePatterns(List<String> sources, String label) {
        List<Pattern> compiled = new ArrayList<>(sources.size());
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i);
            try {
                compiled.add(Pattern.compile(source, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            catch (PatternSyntaxException ex) {
                throw new IllegalStateException(
                        "Invalid app.rag.routing." + label + "[" + i + "]: " + source, ex);
            }
        }
        return List.copyOf(compiled);
    }

    static double bestDistance(List<Document> documents) {
        double best = Double.MAX_VALUE;
        for (Document document : documents) {
            Object distance = document.getMetadata().get("distance");
            if (distance instanceof Number number) {
                best = Math.min(best, number.doubleValue());
            }
        }
        return best == Double.MAX_VALUE ? 1.0 : best;
    }

    private static String formatDistance(double distance) {
        return String.format(Locale.ROOT, "%.4f", distance);
    }

    public record Decision(boolean useRag, boolean shouldRetrieve, String reason) {

        static Decision skip(String reason) {
            return new Decision(false, false, reason);
        }

        static Decision retrieve(String reason) {
            return new Decision(false, true, reason);
        }

        static Decision use(String reason) {
            return new Decision(true, false, reason);
        }
    }
}
