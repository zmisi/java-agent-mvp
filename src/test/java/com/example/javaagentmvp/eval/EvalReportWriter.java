package com.example.javaagentmvp.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class EvalReportWriter {

    private EvalReportWriter() {
    }

    public static void writeMarkdown(Path output, String title, List<EvalResult> results) throws IOException {
        Files.createDirectories(output.getParent());
        long passed = results.stream().filter(EvalResult::passed).count();
        double passRate = results.isEmpty() ? 0.0 : (passed * 100.0 / results.size());
        double avgLatency = results.stream().mapToLong(EvalResult::latencyMs).average().orElse(0.0);

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append("Generated: ").append(Instant.now()).append("\n\n");
        sb.append("- Total: ").append(results.size()).append("\n");
        sb.append("- Passed: ").append(passed).append("\n");
        sb.append("- Pass rate: ").append(String.format("%.1f%%", passRate)).append("\n");
        sb.append("- Avg latency: ").append(String.format("%.0f ms", avgLatency)).append("\n\n");
        sb.append("| Case | Status | Latency | Notes |\n");
        sb.append("|------|--------|---------|-------|\n");
        for (EvalResult result : results) {
            sb.append("| ").append(result.caseId())
                    .append(" | ").append(result.passed() ? "PASS" : "FAIL")
                    .append(" | ").append(result.latencyMs()).append(" ms")
                    .append(" | ").append(escape(result.notes()))
                    .append(" |\n");
        }
        Files.writeString(output, sb.toString());
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|").replace("\n", " ");
    }

    public record EvalResult(String caseId, boolean passed, long latencyMs, String notes) {
    }
}
