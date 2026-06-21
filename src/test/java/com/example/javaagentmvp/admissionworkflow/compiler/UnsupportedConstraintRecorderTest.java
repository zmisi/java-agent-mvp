package com.example.javaagentmvp.admissionworkflow.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnsupportedConstraintRecorderTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        CompileRecordContext.clear();
    }

    @Test
    void recordsFullUserMessageAndRandomUuidPerConstraint() throws Exception {
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class)))
                .thenReturn(1);
        UnsupportedConstraintRecorder recorder = new UnsupportedConstraintRecorder(
                jdbcTemplate,
                AdmissionCompilerProperties.defaults());
        CompileRecordContext.set("conv-1", 42L, "chat", "只看就业率高的专业");

        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                new AdmissionSlotsIr(600, null, List.of("安徽"), "物理类", null, null),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(new UnsupportedConstraintIr("就业率高", "employment_data", "no_employment_data", "就业数据/就业率")),
                List.of(),
                0.8,
                "只看就业率高的专业",
                null);

        recorder.record(query, "local");

        ArgumentCaptor<PreparedStatementSetter> setterCaptor = ArgumentCaptor.forClass(PreparedStatementSetter.class);
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), setterCaptor.capture());

        PreparedStatement ps = mock(PreparedStatement.class);
        setterCaptor.getValue().setValues(ps);

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(ps).setObject(org.mockito.ArgumentMatchers.eq(1), idCaptor.capture(), org.mockito.ArgumentMatchers.eq(Types.OTHER));
        assertThat(idCaptor.getValue()).isNotNull();
        verify(ps).setString(2, "conv-1");
        verify(ps).setLong(3, 42L);
        verify(ps).setString(6, "只看就业率高的专业");
        verify(ps).setString(10, "local");
        verify(ps).setTimestamp(
                org.mockito.ArgumentMatchers.eq(11),
                org.mockito.ArgumentMatchers.any(Timestamp.class));
    }

    @Test
    void recordsEachUnsupportedConstraintOnSameTurn() {
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class)))
                .thenReturn(1);
        UnsupportedConstraintRecorder recorder = new UnsupportedConstraintRecorder(
                jdbcTemplate,
                AdmissionCompilerProperties.defaults());
        CompileRecordContext.set("conv-2", null, "guest", "就业好、薪资高的工科");

        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                AdmissionSlotsIr.empty(),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(
                        new UnsupportedConstraintIr("就业好", "employment_data", "no_employment_data", "就业数据/就业率"),
                        new UnsupportedConstraintIr("薪资高", "salary_data", "no_salary_data", "薪资/收入")),
                List.of(),
                0.8,
                "就业好、薪资高的工科",
                null);

        recorder.record(query, "local");

        verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .update(anyString(), org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class));
    }

    @Test
    void skipsWhenRecordingDisabled() {
        UnsupportedConstraintRecorder recorder = new UnsupportedConstraintRecorder(
                jdbcTemplate,
                new AdmissionCompilerProperties(false, "http://localhost:8090", 3_000, true, false));
        AdmissionQueryIr query = new AdmissionQueryIr(
                "search_majors",
                AdmissionSlotsIr.empty(),
                AdmissionFiltersIr.empty(),
                List.of(),
                List.of(),
                List.of(new UnsupportedConstraintIr("就业", "employment_data", "no_employment_data", "就业数据/就业率")),
                List.of(),
                0.8,
                "就业",
                null);

        recorder.record(query, "local");

        verify(jdbcTemplate, org.mockito.Mockito.never())
                .update(anyString(), org.mockito.ArgumentMatchers.any(PreparedStatementSetter.class));
    }
}
