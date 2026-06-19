package com.example.javaagentmvp.admissionworkflow.planner;

import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionPreferenceIr;
import com.example.javaagentmvp.admissionworkflow.compiler.AdmissionQueryIr;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class QueryPlanner {

    private QueryPlanner() {
    }

    public static String buildPreferenceRetrievalQuery(AdmissionQueryIr query) {
        if (query == null || query.preferences().isEmpty()) {
            return "";
        }
        Set<String> terms = new LinkedHashSet<>();
        for (AdmissionPreferenceIr preference : query.preferences()) {
            terms.addAll(preferenceTerms(preference.dimension()));
        }
        StringBuilder builder = new StringBuilder(String.join(" ", terms));
        if (query.rawMessage() != null && !query.rawMessage().isBlank()) {
            builder.append(' ').append(query.rawMessage().strip());
        }
        return builder.toString().strip();
    }

    public static List<String> preferenceTerms(String dimension) {
        return switch (dimension) {
            case "employment_outlook" -> List.of("就业", "就业率", "就业前景", "去向");
            case "salary" -> List.of("薪资", "收入", "薪酬", "待遇");
            case "state_owned_employability" -> List.of("央企", "国企", "国有", "机关事业单位", "就业");
            default -> List.of();
        };
    }

    public static List<String> preferenceMajorBoostKeywords(AdmissionQueryIr query) {
        if (query == null || query.preferences().isEmpty()) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (AdmissionPreferenceIr preference : query.preferences()) {
            keywords.addAll(switch (preference.dimension()) {
                case "employment_outlook" -> List.of(
                        "计算机", "软件", "电子信息", "自动化", "电气", "人工智能", "数据");
                case "salary" -> List.of(
                        "计算机", "软件", "金融", "电子信息", "人工智能", "电气", "自动化");
                case "state_owned_employability" -> List.of(
                        "电气", "自动化", "计算机", "软件", "土木", "机械", "财会", "金融", "石油", "矿业", "交通");
                default -> List.of();
            });
        }
        return new ArrayList<>(keywords);
    }
}
