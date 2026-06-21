package com.example.javaagentmvp.admissionworkflow.compiler;

import com.example.javaagentmvp.admissionworkflow.intent.AdmissionInputParser;

import java.util.List;
import java.util.regex.Pattern;

/** Detects unresolved major-category filter intent and builds clarify prompts. */
public final class MajorCategoryClarificationSupport {

    public static final String FIELD = "major_category";

    public static final List<String> OPTIONS = List.of("工科", "理科", "文科", "医学", "经管");

    public static final String FIELD_PROMPT =
            "专业大类（" + String.join("/", OPTIONS) + "）";

    private static final Pattern DIRECTIVE_PATTERN = Pattern.compile(
            "(?:只看|只要|仅|限定|筛选|限制)([^，。！？；\\s]{1,16})");
    private static final Pattern CATEGORY_SUFFIX_PATTERN = Pattern.compile(
            "([^，。！？；\\s]{2,10})(?:专业大类|学科门类)");
    private static final Pattern CLASS_SUFFIX_PATTERN = Pattern.compile(
            "(?:只看|只要|仅)?([^，。！？；\\s]{2,8})类专业");

    private MajorCategoryClarificationSupport() {
    }

    public static boolean needsMajorCategoryClarification(
            String message,
            AdmissionOntologyRegistry.MajorCategoryMatch majorCategory) {
        if (message == null || message.isBlank()) {
            return false;
        }
        if (majorCategory != null && !majorCategory.matchedPhrases().isEmpty()) {
            return false;
        }
        if (!AdmissionInputParser.parseIncludeMajorKeywords(message).isEmpty()) {
            return false;
        }
        return expressesCategoryFilterIntent(message);
    }

    static boolean expressesCategoryFilterIntent(String message) {
        if (message.contains("专业大类") || message.contains("学科门类")) {
            return true;
        }
        if (DIRECTIVE_PATTERN.matcher(message).find()) {
            return true;
        }
        if (CATEGORY_SUFFIX_PATTERN.matcher(message).find()) {
            return true;
        }
        return CLASS_SUFFIX_PATTERN.matcher(message).find();
    }
}
