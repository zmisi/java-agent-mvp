package com.example.javaagentmvp.chat.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChatTableEnrichmentService {

    private final UniversityProfileRepository universityProfileRepository;
    private final UniversityProfileProperties properties;
    private final Path logoDir;
    private final MajorHistoryEnrichmentService majorHistoryEnrichmentService;

    public ChatTableEnrichmentService(
            UniversityProfileRepository universityProfileRepository,
            UniversityProfileProperties properties,
            MajorHistoryEnrichmentService majorHistoryEnrichmentService) {
        this.universityProfileRepository = universityProfileRepository;
        this.properties = properties == null ? UniversityProfileProperties.defaults() : properties;
        this.logoDir = Path.of(this.properties.logoDir()).toAbsolutePath().normalize();
        this.majorHistoryEnrichmentService = majorHistoryEnrichmentService;
    }

    /** Grouping/logo enrichment only — avoids history MCP and circular noop wiring in tests. */
    public static ChatTableEnrichmentService profilesOnly() {
        return new ChatTableEnrichmentService(null, new UniversityProfileProperties(false, "."), null);
    }

    public static ChatTableEnrichmentService noop() {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ChatTableEnrichmentService(
                null,
                new UniversityProfileProperties(false, "."),
                new MajorHistoryEnrichmentService(MajorHistoryMcpClient.noop(objectMapper)));
    }

    public List<ChatTable> enrichTables(List<ChatTable> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        List<ChatTable> enriched = tables.stream().map(this::enrichTable).toList();
        if (majorHistoryEnrichmentService == null) {
            return enriched;
        }
        return majorHistoryEnrichmentService.enrichWithHistory(enriched);
    }

    public ChatTable enrichTable(ChatTable table) {
        if (table == null) {
            return null;
        }
        ChatTable grouped = ChatTableGrouper.withGroups(table);
        if (!properties.enabled() || universityProfileRepository == null) {
            return grouped;
        }
        if (grouped.groups() == null || grouped.groups().isEmpty()) {
            return grouped;
        }
        List<ChatTableGroup> enrichedGroups = enrichGroups(grouped.groups());
        return new ChatTable(
                grouped.title(),
                grouped.columns(),
                grouped.rows(),
                enrichedGroups,
                grouped.province());
    }

    private List<ChatTableGroup> enrichGroups(List<ChatTableGroup> groups) {
        Set<String> names = new LinkedHashSet<>();
        for (ChatTableGroup group : groups) {
            if (UniversityTagDisplay.hasUsefulText(group.universityName())) {
                names.add(group.universityName().strip());
            }
        }
        Map<String, UniversityProfile> profiles = universityProfileRepository.findByNames(names);
        List<ChatTableGroup> enriched = new ArrayList<>(groups.size());
        for (ChatTableGroup group : groups) {
            enriched.add(enrichGroup(group, profiles.get(group.universityName())));
        }
        return enriched;
    }

    private ChatTableGroup enrichGroup(ChatTableGroup group, UniversityProfile profile) {
        if (profile == null) {
            return group;
        }
        return new ChatTableGroup(
                group.universityCode(),
                group.universityName(),
                group.majorCount(),
                group.minScore(),
                group.majors(),
                UniversityLogoUrls.resolve(profile, logoDir),
                profile.province(),
                UniversityTagDisplay.formatDepartment(profile.department()),
                UniversityTagDisplay.format(profile.tags(), profile.schoolLevel()));
    }
}
