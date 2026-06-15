package com.example.javaagentmvp.admissionworkflow.async;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import com.example.javaagentmvp.admissionworkflow.AsyncProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowJobQueueTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ListOperations<String, String> listOperations;

    private WorkflowJobQueue queue;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        queue = new WorkflowJobQueue(
                redisTemplate,
                new AdmissionWorkflowProperties(
                        true,
                        "admission_report",
                        false,
                        null,
                        new AsyncProperties(true, "admission-workflow:jobs", true, 5)));
    }

    @Test
    void enqueueLeftPushesRunId() {
        queue.enqueue("run-abc");

        verify(listOperations).leftPush("admission-workflow:jobs", "run-abc");
    }

    @Test
    void dequeueUsesBrpopWithTimeout() {
        when(listOperations.rightPop("admission-workflow:jobs", 5, TimeUnit.SECONDS)).thenReturn("run-xyz");

        assertThat(queue.dequeue()).contains("run-xyz");
    }

    @Test
    void dequeueReturnsEmptyWhenRedisTimesOut() {
        when(listOperations.rightPop(eq("admission-workflow:jobs"), eq(5L), eq(TimeUnit.SECONDS))).thenReturn(null);

        assertThat(queue.dequeue()).isEmpty();
    }
}
