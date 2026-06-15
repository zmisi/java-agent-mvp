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
                    node.path("maxLatencyMs").asLong(60_000)));
        }
        return cases;
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
            long maxLatencyMs) {
    }
}
