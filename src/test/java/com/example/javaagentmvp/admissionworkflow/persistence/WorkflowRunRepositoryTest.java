package com.example.javaagentmvp.admissionworkflow.persistence;

import com.example.javaagentmvp.admissionworkflow.persistence.mapper.WorkflowCheckpointMapper;
import com.example.javaagentmvp.admissionworkflow.persistence.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRunRepositoryTest {

    @Mock
    private WorkflowRunMapper workflowRunMapper;

    @Mock
    private WorkflowCheckpointMapper workflowCheckpointMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private WorkflowRunRepository workflowRunRepository;

    @Test
    void tryMarkRunningReturnsTrueWhenRowUpdated() {
        when(workflowRunMapper.markRunningIfPending(eq("run-1"), any(Instant.class))).thenReturn(1);

        assertThat(workflowRunRepository.tryMarkRunning("run-1")).isTrue();
    }

    @Test
    void tryMarkRunningReturnsFalseWhenAlreadyRunning() {
        when(workflowRunMapper.markRunningIfPending(eq("run-1"), any(Instant.class))).thenReturn(0);

        assertThat(workflowRunRepository.tryMarkRunning("run-1")).isFalse();
    }

    @Test
    void createPendingRunInsertsPendingStatus() {
        when(workflowRunMapper.insert(any())).thenReturn(1);

        String runId = workflowRunRepository.createPendingRun("admission_report", 9L, "conv", "hello");

        assertThat(runId).isNotBlank();
        verify(workflowRunMapper).insert(any());
    }
}
