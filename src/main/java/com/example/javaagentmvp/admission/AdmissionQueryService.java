package com.example.javaagentmvp.admission;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AdmissionQueryService {

    private static final List<AdmissionRow> MOCK_ROWS = List.of(
            new AdmissionRow("合肥工业大学", "计算机科学与技术", 632, "安徽", "物理类", 2025, "普通批", "合肥校区"),
            new AdmissionRow("合肥工业大学", "土木工程", 620, "安徽", "物理类", 2025, "普通批", "合肥校区"),
            new AdmissionRow("合肥工业大学", "软件工程", 625, "安徽", "物理类", 2025, "国家专项", "宣城校区"),
            new AdmissionRow("安徽大学", "法学", 603, "安徽", "历史类", 2025, "普通批", "磬苑校区"),
            new AdmissionRow("安徽理工大学", "自动化", 586, "安徽", "物理类", 2024, "中外合作", "合肥校区")
    );

    public AdmissionResult query(AdmissionRequest request) {
        String campusPreference = normalize(request.campusPreference());
        List<AdmissionRow> filtered = MOCK_ROWS.stream()
                .filter(row -> row.province().equals(request.province()))
                .filter(row -> row.subjectType().equals(request.subjectType()))
                .filter(row -> row.year() == request.year())
                .filter(row -> row.admissionType().equals(request.admissionType()))
                .filter(row -> row.minScore() <= request.score())
                .filter(row -> campusPreference == null || normalize(row.campus()).contains(campusPreference))
                .toList();

        List<String> columns = List.of("院校", "专业", "最低分", "省份", "科类", "年份", "招生类型", "校区");
        List<List<Object>> rows = filtered.stream()
                .map(row -> List.<Object>of(
                        row.schoolName(),
                        row.majorName(),
                        row.minScore(),
                        row.province(),
                        row.subjectType(),
                        row.year(),
                        row.admissionType(),
                        row.campus()))
                .toList();

        String summary = filtered.isEmpty()
                ? "未查询到符合条件的结果，请调整筛选条件后重试。"
                : "共匹配到 " + filtered.size() + " 条可报考结果。";
        return new AdmissionResult(summary, columns, rows);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    public record AdmissionRequest(
            int score,
            String province,
            String subjectType,
            int year,
            String admissionType,
            String campusPreference) {
    }

    public record AdmissionResult(
            String summary,
            List<String> columns,
            List<List<Object>> rows) {
    }

    private record AdmissionRow(
            String schoolName,
            String majorName,
            int minScore,
            String province,
            String subjectType,
            int year,
            String admissionType,
            String campus) {
    }
}
