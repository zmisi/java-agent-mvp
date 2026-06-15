package com.example.javaagentmvp.admissionworkflow;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.admission-workflow.async", name = "enabled", havingValue = "true")
@ImportAutoConfiguration(RedisAutoConfiguration.class)
public class AdmissionWorkflowAsyncConfiguration {
}
