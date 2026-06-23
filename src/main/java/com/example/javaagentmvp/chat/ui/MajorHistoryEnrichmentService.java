package com.example.javaagentmvp.chat.ui;

import com.example.javaagentmvp.admissionworkflow.DefaultAdmissionYear;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MajorHistoryEnrichmentService {

    private static final Pattern TITLE_YEAR = Pattern.compile("\\b(20\\d{2})\\b");
    private static final Pattern TITLE_YEAR_SEGMENT = Pattern.compile("^20\\d{2}$");
    private static final Pattern RANK_OR_SCORE_SEGMENT = Pattern.compile(".*(排名|位次|分|名).*");
    private static final Set<String> NON_PROVINCE_SEGMENTS = Set.of(
            "物理类", "历史类", "物理组", "历史组", "物理", "历史",
            "普通批", "普通", "本科批", "国家专项", "地方专项", "中外合作", "艺术类");

    private final MajorHistoryMcpClient majorHistoryMcpClient;

    public MajorHistoryEnrichmentService(MajorHistoryMcpClient majorHistoryMcpClient) {
        this.majorHistoryMcpClient = majorHistoryMcpClient;
    }

    public List<ChatTable> enrichWithHistory(List<ChatTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<ChatTable> majorTables = tables.stream().filter(this::isMajorTierTable).toList();
        if (majorTables.isEmpty() || alreadyHasHistory(majorTables)) {
            return tables;
        }

        int baseYear = resolveBaseYear(majorTables);
        if (baseYear != DefaultAdmissionYear.VALUE) {
            return tables;
        }

        String province = resolveProvince(majorTables);
        if (!isLikelyProvince(province)) {
            return tables;
        }

        List<MajorHistoryMcpClient.MajorHistoryRequest> requests = collectRequests(majorTables);
        if (requests.isEmpty()) {
            return tables;
        }

        Map<MajorHistoryTableExpander.MajorHistoryKey, Map<Integer, Map<String, String>>> history =
                majorHistoryMcpClient.fetchHistory(province, baseYear, requests);
        if (history.isEmpty()) {
            return tables;
        }

        List<ChatTable> enriched = new ArrayList<>(tables.size());
        for (ChatTable table : tables) {
            enriched.add(isMajorTierTable(table) ? expandTableHistory(table, history) : table);
        }
        return enriched;
    }

    private ChatTable expandTableHistory(
            ChatTable table,
            Map<MajorHistoryTableExpander.MajorHistoryKey, Map<Integer, Map<String, String>>> history) {
        if (table.groups() == null || table.groups().isEmpty()) {
            return table;
        }
        List<ChatTableGroup> expandedGroups = new ArrayList<>(table.groups().size());
        for (ChatTableGroup group : table.groups()) {
            List<Map<String, String>> majors = MajorHistoryTableExpander.expandGroupMajors(
                    group.majors(),
                    group.universityCode(),
                    history);
            expandedGroups.add(new ChatTableGroup(
                    group.universityCode(),
                    group.universityName(),
                    group.majorCount(),
                    group.minScore(),
                    majors,
                    group.logoUrl(),
                    group.province(),
                    group.department(),
                    group.tags()));
        }
        return new ChatTable(table.title(), table.columns(), table.rows(), expandedGroups, table.province());
    }

    private List<MajorHistoryMcpClient.MajorHistoryRequest> collectRequests(List<ChatTable> tables) {
        Map<String, MajorHistoryMcpClient.MajorHistoryRequest> requests = new LinkedHashMap<>();
        for (ChatTable table : tables) {
            if (table.groups() == null) {
                continue;
            }
            for (ChatTableGroup group : table.groups()) {
                if (group.universityCode() == null || group.universityCode().isBlank() || "-".equals(group.universityCode())) {
                    continue;
                }
                for (Map<String, String> major : group.majors()) {
                    if (MajorHistoryTableExpander.isHistoryRow(major)) {
                        continue;
                    }
                    MajorHistoryMcpClient.MajorHistoryRequest request = new MajorHistoryMcpClient.MajorHistoryRequest(
                            group.universityCode(),
                            major.get("major_name"),
                            major.get("campus"),
                            major.get("subject_group"),
                            major.get("admission_type"));
                    String key = request.universityCode()
                            + "\u0000"
                            + nullToEmpty(request.majorName())
                            + "\u0000"
                            + nullToEmpty(request.campus())
                            + "\u0000"
                            + nullToEmpty(request.subjectGroup())
                            + "\u0000"
                            + nullToEmpty(request.admissionType());
                    requests.putIfAbsent(key, request);
                }
            }
        }
        return new ArrayList<>(requests.values());
    }

    private static boolean alreadyHasHistory(List<ChatTable> tables) {
        for (ChatTable table : tables) {
            if (table.groups() == null) {
                continue;
            }
            for (ChatTableGroup group : table.groups()) {
                for (Map<String, String> major : group.majors()) {
                    if (MajorHistoryTableExpander.isHistoryRow(major)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isMajorTierTable(ChatTable table) {
        if (table == null || table.columns() == null || table.columns().isEmpty()) {
            return false;
        }
        boolean rankTable = table.columns().stream()
                .anyMatch(column -> "year_label".equals(column.key()) || "rank_range".equals(column.key()));
        if (rankTable) {
            return false;
        }
        return table.columns().stream().anyMatch(column -> "major_name".equals(column.key()));
    }

    private static int resolveBaseYear(List<ChatTable> tables) {
        for (ChatTable table : tables) {
            if (table.groups() != null) {
                for (ChatTableGroup group : table.groups()) {
                    for (Map<String, String> major : group.majors()) {
                        int year = parseYear(major.get("year"));
                        if (year > 0) {
                            return year;
                        }
                    }
                }
            }
            for (Map<String, String> row : table.rows()) {
                int year = parseYear(row.get("year"));
                if (year > 0) {
                    return year;
                }
            }
            int titleYear = parseYearFromTitle(table.title());
            if (titleYear > 0) {
                return titleYear;
            }
        }
        return DefaultAdmissionYear.VALUE;
    }

    private static String resolveProvince(List<ChatTable> tables) {
        for (ChatTable table : tables) {
            if (table.province() != null && !table.province().isBlank()) {
                return table.province().strip();
            }
            String fromTitle = parseProvinceFromTitle(table.title());
            if (fromTitle != null) {
                return fromTitle;
            }
        }
        return null;
    }

    static String parseProvinceFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String inner = title.strip();
        int open = inner.indexOf('（');
        int close = inner.lastIndexOf('）');
        if (open >= 0 && close > open) {
            inner = inner.substring(open + 1, close).strip();
        }
        if (!inner.contains("·")) {
            return null;
        }
        for (String part : inner.split("·")) {
            String candidate = part.replaceAll("[^\\u4e00-\\u9fa5]", "").strip();
            if (!isLikelyProvince(candidate)) {
                continue;
            }
            if (RANK_OR_SCORE_SEGMENT.matcher(part).matches()) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    static boolean isLikelyProvince(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String normalized = candidate.strip();
        if (normalized.length() < 2 || normalized.length() > 4) {
            return false;
        }
        if (NON_PROVINCE_SEGMENTS.contains(normalized)) {
            return false;
        }
        if (TITLE_YEAR_SEGMENT.matcher(normalized).matches()) {
            return false;
        }
        if (normalized.startsWith("冲") || normalized.startsWith("稳") || normalized.startsWith("保")) {
            return false;
        }
        if (normalized.contains("排名") || normalized.contains("位次")) {
            return false;
        }
        return true;
    }

    private static int parseYearFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return -1;
        }
        Matcher matcher = TITLE_YEAR.matcher(title);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return -1;
    }

    private static int parseYear(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.strip());
        }
        catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
