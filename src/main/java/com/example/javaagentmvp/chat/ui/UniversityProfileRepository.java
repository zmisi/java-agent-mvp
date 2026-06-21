package com.example.javaagentmvp.chat.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
class UniversityProfileRepository {

    private static final Logger log = LoggerFactory.getLogger(UniversityProfileRepository.class);

    private static final String BASE_LOOKUP_SQL = """
            SELECT name, yxdm, province, department, school_level,
                   is_double_first_class, has_graduate_school
            FROM admissions.chsi_university
            WHERE name = ANY (?::text[])
            """;

    private static final String EXTENDED_LOOKUP_SQL = """
            SELECT name, yxdm, province, department, school_level,
                   is_double_first_class, has_graduate_school,
                   is_985, is_211, tags, logo_url
            FROM admissions.chsi_university
            WHERE name = ANY (?::text[])
            """;

    private final JdbcTemplate jdbcTemplate;
    private final UniversityYxdmTagRegistry yxdmTagRegistry;
    private Boolean tableAvailable;
    private Boolean extendedColumnsAvailable;

    UniversityProfileRepository(JdbcTemplate jdbcTemplate, UniversityYxdmTagRegistry yxdmTagRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.yxdmTagRegistry = yxdmTagRegistry;
    }

    Map<String, UniversityProfile> findByNames(Collection<String> names) {
        if (names == null || names.isEmpty() || !isTableAvailable()) {
            return Map.of();
        }
        List<String> queryNames = names.stream()
                .filter(UniversityTagDisplay::hasUsefulText)
                .map(String::strip)
                .distinct()
                .toList();
        if (queryNames.isEmpty()) {
            return Map.of();
        }

        try {
            String sql = hasExtendedColumns() ? EXTENDED_LOOKUP_SQL : BASE_LOOKUP_SQL;
            return jdbcTemplate.query(
                    sql,
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("text", queryNames.toArray())),
                    rs -> {
                        Map<String, UniversityProfile> profiles = new LinkedHashMap<>();
                        while (rs.next()) {
                            UniversityProfile profile = toProfile(rs, hasExtendedColumns());
                            profiles.putIfAbsent(profile.name(), profile);
                        }
                        return profiles;
                    });
        }
        catch (DataAccessException ex) {
            log.warn("University profile lookup failed; continuing without enrichment: {}", ex.getMessage());
            return Map.of();
        }
    }

    private UniversityProfile toProfile(ResultSet rs, boolean extended) throws SQLException {
        String yxdm = rs.getString("yxdm");
        List<String> tags = extended
                ? mergeTags(
                        readTags(rs.getArray("tags")),
                        rs.getBoolean("is_985"),
                        rs.getBoolean("is_211"),
                        rs.getBoolean("is_double_first_class"),
                        rs.getBoolean("has_graduate_school"))
                : buildTags(
                        rs.getBoolean("is_double_first_class"),
                        rs.getBoolean("has_graduate_school"));
        tags = UniversityYxdmTagRegistry.unionTags(tags, yxdmTagRegistry.tagsForYxdm(yxdm));
        String logoUrl = extended ? rs.getString("logo_url") : null;
        return new UniversityProfile(
                rs.getString("name"),
                yxdm,
                rs.getString("province"),
                rs.getString("department"),
                rs.getString("school_level"),
                tags,
                logoUrl);
    }

    static List<String> buildTags(boolean isDoubleFirstClass, boolean hasGraduateSchool) {
        List<String> tags = new ArrayList<>();
        if (isDoubleFirstClass) {
            tags.add("双一流");
        }
        if (hasGraduateSchool) {
            tags.add("研究生院");
        }
        return List.copyOf(tags);
    }

    static List<String> mergeTags(
            List<String> dbTags,
            boolean is985,
            boolean is211,
            boolean isDoubleFirstClass,
            boolean hasGraduateSchool) {
        Set<String> tags = new LinkedHashSet<>();
        if (dbTags != null) {
            tags.addAll(dbTags);
        }
        if (is985) {
            tags.add("985");
        }
        if (is211) {
            tags.add("211");
        }
        if (isDoubleFirstClass) {
            tags.add("双一流");
        }
        if (hasGraduateSchool) {
            tags.add("研究生院");
        }
        return UniversityYxdmTagRegistry.sortTags(tags);
    }

    private static List<String> readTags(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        Object value = sqlArray.getArray();
        if (value instanceof String[] tags) {
            return List.of(tags);
        }
        return List.of();
    }

    private boolean isTableAvailable() {
        if (tableAvailable != null) {
            return tableAvailable;
        }
        tableAvailable = queryExists("""
                SELECT EXISTS (
                  SELECT 1 FROM information_schema.tables
                  WHERE table_schema = 'admissions' AND table_name = 'chsi_university'
                )
                """);
        return tableAvailable;
    }

    private boolean hasExtendedColumns() {
        if (!isTableAvailable()) {
            return false;
        }
        if (extendedColumnsAvailable != null) {
            return extendedColumnsAvailable;
        }
        extendedColumnsAvailable = queryExists("""
                SELECT EXISTS (
                  SELECT 1 FROM information_schema.columns
                  WHERE table_schema = 'admissions'
                    AND table_name = 'chsi_university'
                    AND column_name = 'tags'
                )
                """);
        return extendedColumnsAvailable;
    }

    private boolean queryExists(String sql) {
        try {
            return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class));
        }
        catch (DataAccessException ex) {
            log.debug("Schema probe failed: {}", ex.getMessage());
            return false;
        }
    }
}
