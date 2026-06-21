package com.example.javaagentmvp.admissionworkflow.format;

import com.example.javaagentmvp.chat.ui.McpTableContext;
import com.example.javaagentmvp.chat.ui.McpTableExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Optional;

/** Builds 冲/稳/保 tiers from {@code getMajorByScore} rows (score ±15), used when rank tiers are sparse. */
public final class ScoreTierMajorSupport {

    public static final int SCORE_TIER_DELTA = 15;
    public static final int MIN_SCORE = 200;
    public static final int MAX_SCORE = 750;
    public static final int SPARSE_RANK_TOTAL_THRESHOLD = 50;
    public static final int SPARSE_RANK_STEADY_THRESHOLD = 10;

    private static final String TIER_REACH = "冲";
    private static final String TIER_STEADY = "稳";
    private static final String TIER_SAFE = "保";

    private ScoreTierMajorSupport() {
    }

    public static int mcpQueryScore(int userScore) {
        return clampScore(userScore + SCORE_TIER_DELTA);
    }

    public static boolean isSparseRankResult(JsonNode root) {
        if (root == null || !root.isObject()) {
            return true;
        }
        JsonNode tierCounts = root.get("tier_counts");
        if (tierCounts != null && tierCounts.isObject()) {
            int reach = tierCounts.path(TIER_REACH).asInt(0);
            int steady = tierCounts.path(TIER_STEADY).asInt(0);
            int safe = tierCounts.path(TIER_SAFE).asInt(0);
            int total = reach + steady + safe;
            if (total == 0) {
                return true;
            }
            return total < SPARSE_RANK_TOTAL_THRESHOLD || (reach == 0 && steady < SPARSE_RANK_STEADY_THRESHOLD);
        }
        JsonNode byTier = root.get("majors_by_tier");
        if (byTier != null && byTier.isObject()) {
            int reach = byTier.path(TIER_REACH).size();
            int steady = byTier.path(TIER_STEADY).size();
            int safe = byTier.path(TIER_SAFE).size();
            int total = reach + steady + safe;
            if (total == 0) {
                return true;
            }
            return total < SPARSE_RANK_TOTAL_THRESHOLD || (reach == 0 && steady < SPARSE_RANK_STEADY_THRESHOLD);
        }
        return root.path("count").asInt(0) < SPARSE_RANK_TOTAL_THRESHOLD;
    }

    public static ObjectNode buildTieredResponse(
            int userScore,
            JsonNode majorsNode,
            ObjectMapper objectMapper,
            Integer resolvedRank) {
        TierBuckets buckets = classifyMajors(majorsNode, userScore, objectMapper);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("user_score", userScore);
        response.put("tier_delta", SCORE_TIER_DELTA);
        response.put("tier_classification", "score");
        if (resolvedRank != null) {
            response.put("resolved_rank", resolvedRank);
        }
        ObjectNode tierCounts = objectMapper.createObjectNode();
        tierCounts.put(TIER_REACH, buckets.reach().size());
        tierCounts.put(TIER_STEADY, buckets.steady().size());
        tierCounts.put(TIER_SAFE, buckets.safe().size());
        response.set("tier_counts", tierCounts);
        ObjectNode majorsByTier = objectMapper.createObjectNode();
        majorsByTier.set(TIER_REACH, buckets.reach());
        majorsByTier.set(TIER_STEADY, buckets.steady());
        majorsByTier.set(TIER_SAFE, buckets.safe());
        response.set("majors_by_tier", majorsByTier);
        ArrayNode allMajors = objectMapper.createArrayNode();
        buckets.reach().forEach(allMajors::add);
        buckets.steady().forEach(allMajors::add);
        buckets.safe().forEach(allMajors::add);
        response.set("majors", allMajors);
        response.put("count", allMajors.size());
        return response;
    }

    public static void captureScoreTierTables(
            String toolInput,
            ObjectNode tieredResponse,
            McpTableExtractor extractor) {
        JsonNode byTier = tieredResponse.get("majors_by_tier");
        if (byTier == null || !byTier.isObject()) {
            return;
        }
        for (String tier : new String[] {TIER_REACH, TIER_STEADY, TIER_SAFE}) {
            JsonNode majors = byTier.get(tier);
            if (majors != null && majors.isArray()) {
                extractor.extractMajorByScoreFromMajors(toolInput, majors, tier)
                        .ifPresent(McpTableContext::add);
            }
        }
    }

    public static Optional<ObjectNode> fallbackFromMajorByScoreResponse(
            int userScore,
            String majorByScoreResponse,
            Integer resolvedRank,
            String tierTableToolInput,
            ObjectMapper objectMapper,
            McpTableExtractor extractor) {
        JsonNode root;
        try {
            root = objectMapper.readTree(extractor.unwrapToolPayload(majorByScoreResponse));
        }
        catch (Exception ex) {
            return Optional.empty();
        }
        JsonNode majorsNode = root.get("majors");
        if (majorsNode == null || !majorsNode.isArray() || majorsNode.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode tiered = buildTieredResponse(userScore, majorsNode, objectMapper, resolvedRank);
        captureScoreTierTables(tierTableToolInput, tiered, extractor);
        return Optional.of(tiered);
    }

    private static TierBuckets classifyMajors(JsonNode majorsNode, int baseScore, ObjectMapper objectMapper) {
        ArrayNode reach = objectMapper.createArrayNode();
        ArrayNode steady = objectMapper.createArrayNode();
        ArrayNode safe = objectMapper.createArrayNode();
        double safeThreshold = baseScore - SCORE_TIER_DELTA;
        double reachUpper = baseScore + SCORE_TIER_DELTA;

        for (JsonNode major : majorsNode) {
            Double minScore = parseMinScore(major.get("min_score"));
            if (minScore == null) {
                steady.add(major);
                continue;
            }
            if (minScore > baseScore && minScore <= reachUpper) {
                reach.add(major);
            }
            else if (minScore <= baseScore && minScore > safeThreshold) {
                steady.add(major);
            }
            else if (minScore <= safeThreshold) {
                safe.add(major);
            }
        }
        return new TierBuckets(reach, steady, safe);
    }

    private static Double parseMinScore(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        String text = node.asText("").strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        }
        catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int clampScore(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private record TierBuckets(ArrayNode reach, ArrayNode steady, ArrayNode safe) {
    }
}
