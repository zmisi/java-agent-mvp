package com.example.javaagentmvp.admissionworkflow.compiler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

@Component
public class IntentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(IntentServiceClient.class);

    private final AdmissionCompilerProperties properties;
    private final RestClient restClient;

    public IntentServiceClient(
            AdmissionCompilerProperties properties,
            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClient = restClientBuilder
                .baseUrl(properties.baseUrl())
                .build();
    }

    public Optional<AdmissionQueryIr> compile(String message) {
        return compile(message, AdmissionSlotsIr.empty(), List.of());
    }

    public Optional<AdmissionQueryIr> compile(
            String message,
            AdmissionSlotsIr priorSlots,
            List<String> priorUserMessages) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            CompilerCompileResponse response = restClient.post()
                    .uri("/compile")
                    .body(CompilerCompileRequest.of(message, priorSlots, priorUserMessages))
                    .retrieve()
                    .body(CompilerCompileResponse.class);
            if (response == null || response.query() == null) {
                log.warn("admission-compiler returned empty body for message={}", message);
                return Optional.empty();
            }
            log.info(
                    "admission-compiler task={} provinces={} needsClarification={}",
                    response.query().task(),
                    response.query().slots().provincesOrEmpty(),
                    response.query().needsClarification());
            return Optional.of(response.query());
        }
        catch (RestClientException ex) {
            log.warn("admission-compiler call failed ({}): {}", properties.baseUrl(), ex.getMessage());
            return Optional.empty();
        }
    }
}
