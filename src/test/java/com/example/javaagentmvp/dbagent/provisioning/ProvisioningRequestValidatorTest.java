package com.example.javaagentmvp.dbagent.provisioning;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProvisioningRequestValidatorTest {

    @Test
    void acceptsValidRequest() {
        assertDoesNotThrow(() -> ProvisioningRequestValidator.validateCreate(
                "10.0.0.1",
                22,
                "ubuntu",
                "PASSWORD",
                "secret",
                null,
                "appdb",
                "app",
                18,
                "ubuntu",
                2048,
                20,
                List.of("pg_stat_statements")));
    }

    @Test
    void rejectsInvalidDatabaseName() {
        assertThrows(
                ResponseStatusException.class,
                () -> ProvisioningRequestValidator.validateCreate(
                        "host",
                        22,
                        "user",
                        "PASSWORD",
                        "x",
                        null,
                        "Bad-Name",
                        "app",
                        18,
                        "ubuntu",
                        512,
                        10,
                        List.of()));
    }
}
