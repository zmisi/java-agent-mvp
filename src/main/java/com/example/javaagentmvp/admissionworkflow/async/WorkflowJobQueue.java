package com.example.javaagentmvp.admissionworkflow.async;

import com.example.javaagentmvp.admissionworkflow.AdmissionWorkflowProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "app.admission-workflow.async", name = "enabled", havingValue = "true")
public class WorkflowJobQueue {

    private final StringRedisTemplate redisTemplate;
    private final AdmissionWorkflowProperties properties;

    public WorkflowJobQueue(StringRedisTemplate redisTemplate, AdmissionWorkflowProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    public void enqueue(String runId) {
        redisTemplate.opsForList().leftPush(properties.async().queueKey(), runId);
    }

    public Optional<String> dequeue() {
        String runId = redisTemplate.opsForList().rightPop(
                properties.async().queueKey(),
                properties.async().brpopTimeoutSeconds(),
                TimeUnit.SECONDS);
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(runId);
    }
}
