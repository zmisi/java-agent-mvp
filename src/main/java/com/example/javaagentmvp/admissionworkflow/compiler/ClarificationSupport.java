package com.example.javaagentmvp.admissionworkflow.compiler;

import java.util.List;
import java.util.Map;

public final class ClarificationSupport {

    private static final Map<String, String> FIELD_PROMPTS = Map.of(
            "score", "高考分数",
            "provinces", "意向省份（或区域，如长三角）",
            "subject_group", "高考科类（物理类/历史类）");

    private ClarificationSupport() {
    }

    public static String buildMessage(List<String> needsClarification) {
        if (needsClarification == null || needsClarification.isEmpty()) {
            return "";
        }
        StringBuilder message = new StringBuilder("为了准确查询，请先补充");
        for (int i = 0; i < needsClarification.size(); i++) {
            if (i > 0) {
                message.append(i == needsClarification.size() - 1 ? "和" : "、");
            }
            String field = needsClarification.get(i);
            message.append(FIELD_PROMPTS.getOrDefault(field, field));
        }
        message.append('。');
        return message.toString();
    }
}
