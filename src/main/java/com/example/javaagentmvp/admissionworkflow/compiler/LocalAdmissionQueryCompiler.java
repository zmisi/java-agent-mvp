package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionIntent;
import com.example.javaagentmvp.admissionworkflow.intent.AdmissionRankQuery;
import com.example.javaagentmvp.rag.RagProperties;
import com.example.javaagentmvp.rag.RagQueryRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class LocalAdmissionQueryCompiler {

    private static final Pattern POLICY_PATTERN = Pattern.compile(
            "招生简章|招生章程|章程|简章|政策|规则|专项|转专业|体检|投档|招生计划|录取办法|录取规则");
    private static final Pattern MAJOR_SEARCH_HINT = Pattern.compile(
            "专业|报什么|什么专业|哪些专业|可报|能上什么|报考|报志愿|志愿|什么学校|哪些学校|院校");
    private static final Pattern UNIVERSITY_HINT = Pattern.compile("大学");

    private final AdmissionOntologyRegistry ontologyRegistry;
    private final AdmissionPriorSlotsBuilder priorSlotsBuilder;
    private final RagQueryRouter ragQueryRouter;
    private final RagProperties ragProperties;

    @Autowired
    public LocalAdmissionQueryCompiler(
            AdmissionOntologyRegistry ontologyRegistry,
            AdmissionPriorSlotsBuilder priorSlotsBuilder,
            RagQueryRouter ragQueryRouter,
            RagProperties ragProperties) {
        this.ontologyRegistry = ontologyRegistry;
        this.priorSlotsBuilder = priorSlotsBuilder;
        this.ragQueryRouter = ragQueryRouter;
        this.ragProperties = ragProperties;
    }

    /** Convenience when only {@link RagQueryRouter} is available (e.g. tests). */
    public LocalAdmissionQueryCompiler(
            AdmissionOntologyRegistry ontologyRegistry,
            AdmissionPriorSlotsBuilder priorSlotsBuilder,
            RagQueryRouter ragQueryRouter) {
        this(ontologyRegistry, priorSlotsBuilder, ragQueryRouter, ragQueryRouter.properties());
    }

    public AdmissionQueryIr compile(String message) {
        return compile(message, List.of());
    }

    public AdmissionQueryIr compile(String message, List<String> priorUserMessagesNewestFirst) {
        AdmissionQueryIr current = compileSingle(message);
        if (priorUserMessagesNewestFirst == null || priorUserMessagesNewestFirst.isEmpty()) {
            return current;
        }

        String rawMessage = current.rawMessage();

        AdmissionSlotsIr priorSlots = priorSlotsBuilder.build(priorUserMessagesNewestFirst);
        AdmissionSlotsIr mergedSlots = geographySpecifiedOnCurrentTurn(current)
                ? current.slots().mergedWith(priorSlots.withoutProvinces())
                : current.slots().mergedWith(priorSlots);

        AdmissionInputParser.ParsedAdmissionInput currentParsed = AdmissionInputParser.parse(rawMessage);
        AdmissionIntent explicitIntent = MultiturnIntentSupport.classifyExplicitIntent(rawMessage);
        AdmissionIntent priorIntent = MultiturnIntentSupport.resolvePriorSearchIntent(
                priorUserMessagesNewestFirst, priorSlots);

        String task = current.task();
        boolean inheritedFromPrior = false;
        boolean usePriorSlots = false;
        if (explicitIntent == AdmissionIntent.POLICY || explicitIntent == AdmissionIntent.REPORT) {
            task = toTask(explicitIntent, rawMessage, mergedSlots.score());
        }
        else if (explicitIntent != AdmissionIntent.UNKNOWN) {
            task = toTask(explicitIntent, rawMessage, mergedSlots.score());
            usePriorSlots = true;
        }
        else if ((priorIntent == AdmissionIntent.RANK || priorIntent == AdmissionIntent.SCORE)
                && (MultiturnIntentSupport.canInheritAsFollowUp(rawMessage, currentParsed)
                || geographySpecifiedOnCurrentTurn(current)
                || MultiturnIntentSupport.hasAdmissionRefinement(current))) {
            task = toTask(priorIntent, rawMessage, mergedSlots.score());
            inheritedFromPrior = true;
            usePriorSlots = true;
        }
        else {
            task = current.task();
        }

        AdmissionSlotsIr effectiveSlots = usePriorSlots ? mergedSlots : current.slots();

        AdmissionOntologyRegistry.MajorCategoryMatch majorCategory =
                ontologyRegistry.matchMajorCategoryFilters(rawMessage);
        List<String> needs = computeNeedsClarification(rawMessage, task, effectiveSlots, majorCategory);
        needs = finalizeUnknownTurn(
                rawMessage,
                currentParsed,
                current,
                task,
                effectiveSlots,
                majorCategory,
                needs);
        task = resolveTaskAfterUnknownPromotion(rawMessage, task, currentParsed, current);
        List<UnsupportedConstraintIr> unsupported = ontologyRegistry.matchUnsupportedConstraints(rawMessage);
        AdmissionParseTraceIr parseTrace = current.parseTrace() == null
                ? new AdmissionParseTraceIr(List.of("local_compiler"), List.of(), false, inheritedFromPrior)
                : new AdmissionParseTraceIr(
                        current.parseTrace().rulesApplied(),
                        current.parseTrace().ontologyHits(),
                        current.parseTrace().llmUsed(),
                        inheritedFromPrior);
        return new AdmissionQueryIr(
                task,
                effectiveSlots,
                current.filters(),
                current.preferences(),
                current.regions(),
                unsupported,
                needs,
                current.confidence(),
                current.rawMessage(),
                parseTrace);
    }

    private AdmissionQueryIr compileSingle(String message) {
        String normalized = message == null ? "" : message.strip();
        if (normalized.isEmpty()) {
            return AdmissionQueryIr.empty(normalized);
        }

        AdmissionInputParser.ParsedAdmissionInput parsed = AdmissionInputParser.parse(normalized);
        AdmissionIntent explicitIntent = MultiturnIntentSupport.classifyExplicitIntent(normalized);
        String task = refineTaskFromRouter(toTask(explicitIntent, normalized, parsed.score()), normalized);

        List<AdmissionRegionIr> regions = ontologyRegistry.matchRegions(normalized);
        AdmissionOntologyRegistry.MajorCategoryMatch majorCategory =
                ontologyRegistry.matchMajorCategoryFilters(normalized);
        AdmissionFiltersIr filters = withMajorCategoryFilters(
                withIncludeSchools(
                        withIncludeMajors(
                                ontologyRegistry.matchExclusions(normalized),
                                AdmissionInputParser.parseIncludeMajorKeywords(normalized)),
                        IncludeSchoolSupport.matchIncludeSchools(normalized, ragProperties)),
                majorCategory);
        List<AdmissionPreferenceIr> preferences = ontologyRegistry.matchPreferences(normalized);
        List<UnsupportedConstraintIr> unsupported = ontologyRegistry.matchUnsupportedConstraints(normalized);

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
                parsed.rank(),
                provinces,
                parsed.subjectGroup(),
                parsed.year(),
                parsed.admissionType());

        List<String> ontologyHits = new ArrayList<>();
        regions.forEach(region -> ontologyHits.add(region.phrase()));
        preferences.forEach(pref -> ontologyHits.add(pref.rawPhrase()));
        majorCategory.matchedPhrases().forEach(ontologyHits::add);
        unsupported.forEach(constraint -> ontologyHits.add(constraint.rawPhrase()));

        List<String> needs = computeNeedsClarification(normalized, task, slots, majorCategory);
        AdmissionQueryIr currentTurn = new AdmissionQueryIr(
                task,
                slots,
                filters,
                preferences,
                regions,
                unsupported,
                needs,
                0.0,
                normalized,
                new AdmissionParseTraceIr(List.of("local_compiler"), ontologyHits, false));
        needs = finalizeUnknownTurn(normalized, parsed, currentTurn, task, slots, majorCategory, needs);
        task = resolveTaskAfterUnknownPromotion(normalized, task, parsed, currentTurn);
        return new AdmissionQueryIr(
                task,
                slots,
                filters,
                preferences,
                regions,
                unsupported,
                needs,
                estimateConfidence(task, slots, regions, filters, preferences),
                normalized,
                currentTurn.parseTrace());
    }

    private static List<String> finalizeUnknownTurn(
            String rawMessage,
            AdmissionInputParser.ParsedAdmissionInput parsed,
            AdmissionQueryIr currentTurn,
            String task,
            AdmissionSlotsIr slots,
            AdmissionOntologyRegistry.MajorCategoryMatch majorCategory,
            List<String> needs) {
        if (!"unknown".equals(task)) {
            return needs;
        }
        if (!MultiturnIntentSupport.isCurrentTurnAdmissionRelated(rawMessage, parsed, currentTurn)) {
            return needs;
        }
        String promoted = AdmissionRankQuery.isRankQuery(rawMessage) ? "search_rank" : "search_majors";
        return computeNeedsClarification(rawMessage, promoted, slots, majorCategory);
    }

    private static String resolveTaskAfterUnknownPromotion(
            String rawMessage,
            String task,
            AdmissionInputParser.ParsedAdmissionInput parsed,
            AdmissionQueryIr currentTurn) {
        if (!"unknown".equals(task)) {
            return task;
        }
        if (!MultiturnIntentSupport.isCurrentTurnAdmissionRelated(rawMessage, parsed, currentTurn)) {
            return task;
        }
        if (AdmissionRankQuery.isRankQuery(rawMessage)) {
            return "search_rank";
        }
        return "search_majors";
    }

    private String refineTaskFromRouter(String task, String message) {
        if (!"unknown".equals(task)) {
            return task;
        }
        RagQueryRouter.Decision decision = ragQueryRouter.decide(message);
        if (decision.useRag()) {
            return "policy_qa";
        }
        String reason = decision.reason();
        if (reason != null && reason.contains("getRankByScore")) {
            return "search_rank";
        }
        if (reason != null && reason.contains("getMajorByScore")) {
            return "search_majors";
        }
        return task;
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

    private static AdmissionFiltersIr withIncludeMajors(AdmissionFiltersIr filters, List<String> includeMajors) {
        if (includeMajors == null || includeMajors.isEmpty()) {
            return filters;
        }
        return new AdmissionFiltersIr(
                filters.excludeSchoolNameContains(),
                filters.excludeMajorKeywords(),
                includeMajors,
                filters.includeSchools(),
                filters.includeMajorDisciplineGroups(),
                filters.includeDisciplineCategories());
    }

    private static AdmissionFiltersIr withIncludeSchools(AdmissionFiltersIr filters, List<String> includeSchools) {
        if (includeSchools == null || includeSchools.isEmpty()) {
            return filters;
        }
        return new AdmissionFiltersIr(
                filters.excludeSchoolNameContains(),
                filters.excludeMajorKeywords(),
                filters.includeMajorKeywords(),
                includeSchools,
                filters.includeMajorDisciplineGroups(),
                filters.includeDisciplineCategories());
    }

    private static AdmissionFiltersIr withMajorCategoryFilters(
            AdmissionFiltersIr filters,
            AdmissionOntologyRegistry.MajorCategoryMatch majorCategory) {
        if (majorCategory == null
                || (majorCategory.disciplineGroups().isEmpty() && majorCategory.disciplineCategories().isEmpty())) {
            return filters;
        }
        return new AdmissionFiltersIr(
                filters.excludeSchoolNameContains(),
                filters.excludeMajorKeywords(),
                filters.includeMajorKeywords(),
                filters.includeSchools(),
                majorCategory.disciplineGroups(),
                majorCategory.disciplineCategories());
    }

    private static List<String> computeNeedsClarification(
            String message,
            String task,
            AdmissionSlotsIr slots,
            AdmissionOntologyRegistry.MajorCategoryMatch majorCategory) {
        List<String> needs = new ArrayList<>();
        if ("search_majors".equals(task) || "report".equals(task) || "search_rank".equals(task)) {
            if (!slots.hasScoreOrRank()) {
                needs.add("score");
            }
            if (!"search_rank".equals(task) && slots.provincesOrEmpty().isEmpty()) {
                needs.add("provinces");
            }
            if (!"search_rank".equals(task)
                    && (slots.subjectGroup() == null || slots.subjectGroup().isBlank())) {
                needs.add("subject_group");
            }
            if (MajorCategoryClarificationSupport.needsMajorCategoryClarification(message, majorCategory)) {
                needs.add(MajorCategoryClarificationSupport.FIELD);
            }
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
        if (filters.hasMajorCategoryFilter()) {
            confidence += 0.05;
        }
        if (!preferences.isEmpty()) {
            confidence += 0.04;
        }
        if (slots.score() != null || slots.rank() != null) {
            confidence += 0.05;
        }
        if (!slots.provincesOrEmpty().isEmpty()) {
            confidence += 0.05;
        }
        return Math.min(confidence, 0.98);
    }
}
