package com.example.javaagentmvp.admissionworkflow.service;

import com.example.javaagentmvp.admissionworkflow.ui.WorkflowReportTableBuilder;
import com.example.javaagentmvp.chat.ui.ChatTable;
import com.example.javaagentmvp.rag.RagSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowReportPresenter {

    private final WorkflowReportTableBuilder workflowReportTableBuilder;

    public WorkflowReportPresenter(WorkflowReportTableBuilder workflowReportTableBuilder) {
        this.workflowReportTableBuilder = workflowReportTableBuilder;
    }

    public PresentedWorkflowReport present(String inputMessage, Map<String, Object> result) {
        if (result == null) {
            result = Map.of();
        }
        String assistant = resolveAssistant(result);
        List<ChatTable> tables = workflowReportTableBuilder.buildTables(inputMessage, result);
        List<RagSource> sources = extractPolicySources(result.get("policySources"));
        return new PresentedWorkflowReport(assistant, tables, sources);
    }

    private static String resolveAssistant(Map<String, Object> result) {
        Object report = result.get("report");
        if (report != null && !String.valueOf(report).isBlank()) {
            return String.valueOf(report).strip();
        }
        Object assistant = result.get("assistant");
        if (assistant != null && !String.valueOf(assistant).isBlank()) {
            return String.valueOf(assistant).strip();
        }
        Object summary = result.get("summary");
        if (summary != null && !String.valueOf(summary).isBlank()) {
            return String.valueOf(summary).strip();
        }
        return "（无报告内容）";
    }

    @SuppressWarnings("unchecked")
    private static List<RagSource> extractPolicySources(Object policySourcesObj) {
        if (!(policySourcesObj instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<RagSource> sources = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof RagSource source) {
                sources.add(source);
            }
            else if (item instanceof Map<?, ?> map) {
                sources.add(new RagSource(
                        stringValue(map.get("title")),
                        stringValue(map.get("source")),
                        stringValue(map.get("snippet")),
                        stringValue(map.get("school"))));
            }
        }
        return sources;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record PresentedWorkflowReport(
            String assistant,
            List<ChatTable> tables,
            List<RagSource> sources) {
    }
}
