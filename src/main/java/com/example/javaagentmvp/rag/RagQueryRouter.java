package com.example.javaagentmvp.rag;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
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

    private final ConversationTurnResolver turnResolver;

    private final List<Pattern> ragPatterns;

    private final List<Pattern> databasePatterns;

    public RagQueryRouter(RagProperties ragProperties, ConversationTurnResolver turnResolver) {
        this.ragProperties = ragProperties;
        this.turnResolver = turnResolver;
        this.ragPatterns = compilePatterns(ragProperties.routing().ragPatterns(), "rag-patterns");
        this.databasePatterns = compilePatterns(ragProperties.routing().databasePatterns(), "database-patterns");
        log.info("RAG routing loaded: {} rag pattern(s), {} database pattern(s)",
                ragPatterns.size(), databasePatterns.size());
    }

    public Decision decide(String message) {
        return decide(message, List.of(), List.of());
    }

    public Decision decide(String message, List<String> priorUserMessages, List<String> priorContextHints) {
        ResolvedTurn resolved = turnResolver.resolve(message, priorUserMessages, priorContextHints);
        return decide(resolved, message);
    }

    public Decision decide(ResolvedTurn resolved, String rawMessage) {
        if (!ragProperties.enabled()) {
            return Decision.skip("RAG disabled");
        }
        String normalized = rawMessage == null ? "" : rawMessage.strip();
        if (normalized.isEmpty()) {
            return Decision.skip("empty message");
        }

        if (ragProperties.routeDatabaseQueries() && matchesPatterns(normalized, databasePatterns)) {
            return Decision.skip("structured query — use MCP tools (SQL, getMajorByScore, or getRankByScore)");
        }

        AdmissionIntent intent = resolved == null ? AdmissionIntent.UNKNOWN : resolved.intent();
        if (intent == AdmissionIntent.RANK) {
            return Decision.skip("resolved intent RANK — use MCP getRankByScore");
        }
        if (intent == AdmissionIntent.SCORE) {
            return Decision.skip("resolved intent SCORE — use MCP getMajorByScore");
        }
        if (intent == AdmissionIntent.POLICY) {
            return Decision.use("resolved intent POLICY — use RAG knowledge base");
        }

        if (matchesPatterns(normalized, ragPatterns)) {
            return Decision.use("matched RAG/doc intent");
        }

        return Decision.retrieve("ambiguous — retrieve then score");
    }

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
