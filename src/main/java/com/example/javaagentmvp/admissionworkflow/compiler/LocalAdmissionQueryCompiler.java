package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntentClassifier;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionRankQuery;
import com.example.javaagentmvp.admissionworkflow.intent.ConversationTurnResolver;
import com.example.javaagentmvp.admissionworkflow.intent.ResolvedTurn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class LocalAdmissionQueryCompiler {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则");
    private static final Pattern MAJOR_SEARCH_HINT = Pattern.compile(
            "专业|报什么|什么专业|哪些专业|可报|能上什么|报考|报志愿|志愿|什么学校|哪些学校|院校");
    private static final Pattern UNIVERSITY_HINT = Pattern.compile("大学");

    private final AdmissionIntentClassifier intentClassifier;
    private final AdmissionOntologyRegistry ontologyRegistry;
    private final ConversationTurnResolver turnResolver;
    private final AdmissionPriorSlotsBuilder priorSlotsBuilder;

    public LocalAdmissionQueryCompiler(
            AdmissionIntentClassifier intentClassifier,
            AdmissionOntologyRegistry ontologyRegistry,
            ConversationTurnResolver turnResolver,
            AdmissionPriorSlotsBuilder priorSlotsBuilder) {
        this.intentClassifier = intentClassifier;
        this.ontologyRegistry = ontologyRegistry;
        this.turnResolver = turnResolver;
        this.priorSlotsBuilder = priorSlotsBuilder;
    }

    public AdmissionQueryIr compile(String message) {
        return compile(message, List.of());
    }

    public AdmissionQueryIr compile(String message, List<String> priorUserMessagesNewestFirst) {
        AdmissionQueryIr current = compileSingle(message);
        if (priorUserMessagesNewestFirst == null || priorUserMessagesNewestFirst.isEmpty()) {
            return current;
        }

        AdmissionSlotsIr priorSlots = priorSlotsBuilder.build(priorUserMessagesNewestFirst);
        AdmissionSlotsIr mergedSlots = geographySpecifiedOnCurrentTurn(current)
                ? current.slots().mergedWith(priorSlots.withoutProvinces())
                : current.slots().mergedWith(priorSlots);

        String task = current.task();
        if ("unknown".equals(task)) {
            ResolvedTurn priorTurn = turnResolver.resolve(
                    message,
                    priorUserMessagesNewestFirst,
                    List.of());
            if (priorTurn.intent() != AdmissionIntent.UNKNOWN) {
                task = toTask(priorTurn.intent(), message, mergedSlots.score());
            }
            else if (mergedSlots.score() != null) {
                task = "search_majors";
            }
        }

        List<String> needs = computeNeedsClarification(task, mergedSlots);
        return new AdmissionQueryIr(
                task,
                mergedSlots,
                current.filters(),
                current.preferences(),
                current.regions(),
                needs,
                current.confidence(),
                current.rawMessage(),
                current.parseTrace());
    }

    private AdmissionQueryIr compileSingle(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty()) {
            return AdmissionQueryIr.empty(normalized);
        }

        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(normalized);
        AdmissionIntent intent = intentClassifier.classify(normalized);
        String task = toTask(intent, normalized, parsed.score());

        List<AdmissionRegionIr> regions = ontologyRegistry.matchRegions(normalized);
        AdmissionFiltersIr exclusionFilters = ontologyRegistry.matchExclusions(normalized);
        List<AdmissionPreferenceIr> preferences = ontologyRegistry.matchPreferences(normalized);

        List<String> provinces = new ArrayList<>();
        if (parsed.province() != null) {
            provinces.add(parsed.province());
        }
        for (AdmissionRegionIr region : regions) {
            for (String province : region.provinces()) {
                if (!provinces.contains(province)) {
                    provinces.add(province);
                }
            }
        }

        AdmissionSlotsIr slots = new AdmissionSlotsIr(
                parsed.score(),
                provinces,
                parsed.subjectGroup(),
                parsed.year(),
                parsed.admissionType());

        List<String> ontologyHits = new ArrayList<>();
        regions.forEach(region -> ontologyHits.add(region.phrase()));
        preferences.forEach(pref -> ontologyHits.add(pref.rawPhrase()));

        AdmissionQueryIr query = new AdmissionQueryIr(
                task,
                slots,
                exclusionFilters,
                preferences,
                regions,
                computeNeedsClarification(task, slots),
                estimateConfidence(task, slots, regions, exclusionFilters, preferences),
                normalized,
                new AdmissionParseTraceIr(List.of("local_compiler"), ontologyHits, false));

        return query;
    }

    private static boolean geographySpecifiedOnCurrentTurn(AdmissionQueryIr current) {
        return !current.regions().isEmpty() || !current.slots().provincesOrEmpty().isEmpty();
    }

    private static String toTask(AdmissionIntent intent, String message, Integer score) {
        return switch (intent) {
            case SCORE -> "search_majors";
            case RANK -> "search_rank";
            case POLICY -> "policy_qa";
            case REPORT -> "report";
            case UNKNOWN -> detectTaskFromMessage(message, score);
        };
    }

    private static String detectTaskFromMessage(String message, Integer score) {
        boolean hasPolicy = POLICY_PATTERN.matcher(message).find();
        boolean hasMajorSearch = MAJOR_SEARCH_HINT.matcher(message).find();
        boolean hasUniversity = UNIVERSITY_HINT.matcher(message).find() && !hasPolicy;
        boolean hasRank = AdmissionRankQuery.isRankQuery(message);

        if (score != null && hasPolicy) {
            return "report";
        }
        if (hasRank) {
            return "search_rank";
        }
        if (hasPolicy && !hasMajorSearch && !hasUniversity) {
            return "policy_qa";
        }
        if (hasMajorSearch || hasUniversity) {
            return "search_majors";
        }
        if (score != null) {
            return "search_majors";
        }
        return "unknown";
    }

    private static List<String> computeNeedsClarification(String task, AdmissionSlotsIr slots) {
        List<String> needs = new ArrayList<>();
        if ("search_majors".equals(task) || "report".equals(task)) {
            if (slots.score() == null) {
                needs.add("score");
            }
            if (slots.provincesOrEmpty().isEmpty()) {
                needs.add("provinces");
            }
            if (slots.subjectGroup() == null || slots.subjectGroup().isBlank()) {
                needs.add("subject_group");
            }
        }
        else if ("search_rank".equals(task) && slots.score() == null) {
            needs.add("score");
        }
        return needs;
    }

    private static double estimateConfidence(
            String task,
            AdmissionSlotsIr slots,
            List<AdmissionRegionIr> regions,
            AdmissionFiltersIr filters,
            List<AdmissionPreferenceIr> preferences) {
        if ("unknown".equals(task)) {
            return 0.35;
        }
        double confidence = 0.72;
        if (!regions.isEmpty()) {
            confidence += 0.08;
        }
        if (!filters.excludeSchoolNameContains().isEmpty() || !filters.excludeMajorKeywords().isEmpty()) {
            confidence += 0.06;
        }
        if (!preferences.isEmpty()) {
            confidence += 0.04;
        }
        if (slots.score() != null) {
            confidence += 0.05;
        }
        if (!slots.provincesOrEmpty().isEmpty()) {
            confidence += 0.05;
        }
        return Math.min(confidence, 0.98);
    }
}
