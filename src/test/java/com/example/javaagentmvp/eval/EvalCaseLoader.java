package com.example.javaagentmvp.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class EvalCaseLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalCaseLoader() {
    }

    public static List<IntentClassifyCase> loadIntentCases(Path path) throws IOException {
        List<IntentClassifyCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            cases.add(new IntentClassifyCase(
                    node.path("id").asText(),
                    node.path("input").asText(),
                    node.path("expectIntent").asText()));
        }
        return cases;
    }

    public static List<WorkflowEvalCase> loadWorkflowCases(Path path) throws IOException {
        List<WorkflowEvalCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            cases.add(new WorkflowEvalCase(
                    node.path("id").asText(),
                    node.path("input").asText(),
                    textOrNull(node, "expectIntent"),
                    textOrNull(node, "expectStatus"),
                    readStringList(node, "expectNodesExecuted"),
                    node.path("requireScoreResult").asBoolean(false),
                    node.path("requirePolicySources").asBoolean(false),
                    node.path("requireClarification").asBoolean(false),
                    node.path("maxLatencyMs").asLong(60_000)));
        }
        return cases;
    }

    public static List<ConstraintCompileCase> loadConstraintCompileCases(Path path) throws IOException {
        return loadConstraintCompileCases(path, false);
    }

    public static List<ConstraintCompileCase> loadConstraintCompileCases(Path path, boolean includeTarget)
            throws IOException {
        List<ConstraintCompileCase> cases = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            if (node.has("turns")) {
                continue;
            }
            if (!includeTarget && "target".equals(textOrNull(node, "status"))) {
                continue;
            }
            JsonNode expectNode = node.get("expect");
            cases.add(new ConstraintCompileCase(
                    node.path("id").asText(),
                    node.path("input").asText(),
                    readStringList(node, "prior_user_messages"),
                    expectNode == null || expectNode.isNull() ? ConstraintCompileExpect.empty() : parseExpect(expectNode)));
        }
        return List.copyOf(cases);
    }

    private static ConstraintCompileExpect parseExpect(JsonNode expect) {
        return new ConstraintCompileExpect(
                textOrNull(expect, "task"),
                expect.has("score") && !expect.get("score").isNull() ? expect.get("score").asInt() : null,
                textOrNull(expect, "subject_group"),
                readStringList(expect, "provinces"),
                expect.path("provinces_exact").asBoolean(false),
                readStringList(expect, "exclude_school"),
                readStringList(expect, "exclude_major"),
                readStringList(expect, "include_major"),
                readStringList(expect, "include_major_discipline_groups"),
                readStringList(expect, "include_discipline_categories"),
                readStringList(expect, "preferences"),
                readStringList(expect, "needs_clarification"),
                readStringList(expect, "unsupported_constraints"),
                expect.has("blocks_mcp") && !expect.get("blocks_mcp").isNull()
                        ? expect.get("blocks_mcp").asBoolean()
                        : null);
    }

    public static Path projectRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText();
    }

    private static List<String> readStringList(JsonNode node, String field) {
        JsonNode array = node.get(field);
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    public record IntentClassifyCase(String id, String input, String expectIntent) {
    }

    public record WorkflowEvalCase(
            String id,
            String input,
            String expectIntent,
            String expectStatus,
            List<String> expectNodesExecuted,
            boolean requireScoreResult,
            boolean requirePolicySources,
            boolean requireClarification,
            long maxLatencyMs) {
    }

    public record ConstraintCompileCase(
            String id,
            String input,
            List<String> priorUserMessagesNewestFirst,
            ConstraintCompileExpect expect) {
    }

    public record ConstraintCompileExpect(
            String task,
            Integer score,
            String subjectGroup,
            List<String> provinces,
            boolean provincesExact,
            List<String> excludeSchool,
            List<String> excludeMajor,
            List<String> includeMajor,
            List<String> includeMajorDisciplineGroups,
            List<String> includeDisciplineCategories,
            List<String> preferences,
            List<String> needsClarification,
            List<String> unsupportedConstraints,
            Boolean blocksMcp) {

        static ConstraintCompileExpect empty() {
            return new ConstraintCompileExpect(
                    null,
                    null,
                    null,
                    List.of(),
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    null);
        }
    }
}
