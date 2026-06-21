package com.example.javaagentmvp.admissionworkflow.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class UnsupportedConstraintAckSupport {

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("tuition", "学费/收费"),
            Map.entry("school_nature_public", "公办/民办性质"),
            Map.entry("school_nature_private", "公办/民办性质"),
            Map.entry("tier_985", "985/211/双一流层次"),
            Map.entry("tier_211", "985/211/双一流层次"),
            Map.entry("double_first_class", "985/211/双一流层次"),
            Map.entry("city", "城市/地级市"),
            Map.entry("postgraduate_recommendation_rate", "保研率/推免率"),
            Map.entry("employment_data", "就业数据/就业率"),
            Map.entry("salary_data", "薪资/收入"),
            Map.entry("state_owned_employability", "央国企就业"),
            Map.entry("university_ranking", "大学/QS排名"),
            Map.entry("discipline_assessment", "学科评估/学科排名"),
            Map.entry("campus_life", "宿舍/住宿"),
            Map.entry("scholarship", "奖学金"),
            Map.entry("transfer_major", "转专业政策/比例"));

    private UnsupportedConstraintAckSupport() {
    }

    public static String buildAcknowledgement(List<UnsupportedConstraintIr> constraints) {
        if (constraints == null || constraints.isEmpty()) {
            return "";
        }
        Map<String, String> labelsByType = new LinkedHashMap<>();
        for (UnsupportedConstraintIr constraint : constraints) {
            labelsByType.putIfAbsent(
                    constraint.constraintType(),
                    constraint.displayLabel() != null && !constraint.displayLabel().isBlank()
                            ? constraint.displayLabel()
                            : TYPE_LABELS.getOrDefault(constraint.constraintType(), constraint.rawPhrase()));
        }
        String joined = String.join("、", labelsByType.values());
        return "暂不支持按「" + joined + "」筛选（相关数据完善中，您的需求已记录）。"
                + "以下结果未包含上述条件，请仅作参考。";
    }

    public static String prependAcknowledgement(AdmissionQueryIr query, String message) {
        if (query == null || !query.hasUnsupportedConstraints()) {
            return message;
        }
        String ack = buildAcknowledgement(query.unsupportedConstraints());
        if (message == null || message.isBlank()) {
            return ack;
        }
        if (message.startsWith(ack)) {
            return message;
        }
        return ack + "\n\n" + message;
    }
}
