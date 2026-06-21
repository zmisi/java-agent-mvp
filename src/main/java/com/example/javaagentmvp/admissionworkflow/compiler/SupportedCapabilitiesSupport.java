package com.example.javaagentmvp.admissionworkflow.compiler;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Formats {@code supported_capabilities.yaml} for compiler / synthesis prompts. */
public final class SupportedCapabilitiesSupport {

    private SupportedCapabilitiesSupport() {
    }

    public static String formatPromptBlock() {
        Map<String, Object> root = loadYaml();
        Object node = root.get("supported_capabilities");
        if (!(node instanceof Map<?, ?> capabilities)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 系统当前支持的能力（可确定性执行）\n");
        appendSection(sb, "任务", capabilities.get("tasks"));
        appendSection(sb, "槽位", capabilities.get("slots"));
        appendSection(sb, "筛选", capabilities.get("filters"));
        Object hint = capabilities.get("out_of_scope_hint");
        if (hint != null && !String.valueOf(hint).isBlank()) {
            sb.append("\n**不在上表范围内的条件**（如保研率、就业薪资、QS排名、学费、985/211 等）：")
                    .append("系统无法筛选或编造，须说明暂不支持并已记录需求。\n");
        }
        return sb.toString().strip();
    }

    private static void appendSection(StringBuilder sb, String title, Object itemsNode) {
        if (!(itemsNode instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        sb.append("\n### ").append(title).append('\n');
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String label = text(map.get("label"));
            String detail = text(map.get("detail"));
            String dataSource = text(map.get("data_source"));
            String examples = text(map.get("examples"));
            if (label.isBlank()) {
                continue;
            }
            sb.append("- **").append(label).append("**");
            if (!detail.isBlank()) {
                sb.append("：").append(detail);
            }
            if (!dataSource.isBlank()) {
                sb.append("（").append(dataSource).append("）");
            }
            if (!examples.isBlank()) {
                sb.append("；例：").append(examples);
            }
            sb.append('\n');
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml() {
        ClassPathResource resource = new ClassPathResource("admission-ontology/supported_capabilities.yaml");
        try (InputStream input = resource.getInputStream()) {
            Object loaded = new Yaml().load(input);
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        catch (IOException ex) {
            return Map.of();
        }
        return Map.of();
    }
}
