package com.example.javaagentmvp.web;

import com.example.javaagentmvp.dbagent.provisioning.DbProvisioningService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/db-provisioning")
public class DbProvisioningController {

    private static final Logger log = LoggerFactory.getLogger(DbProvisioningController.class);

    private final DbProvisioningService provisioningService;

    public DbProvisioningController(DbProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @GetMapping
    public List<DbProvisioningService.ProvisioningSummary> list() {
        return provisioningService.list();
    }

    @GetMapping("/{id}")
    public DbProvisioningService.ProvisioningDetail get(@PathVariable String id) {
        return provisioningService.get(id);
    }

    @PostMapping("/preflight")
    public DbProvisioningService.PreflightResult preflight(@RequestBody PreflightProvisioningDto body) {
        log.info(
                "POST /api/db-provisioning/preflight host={} port={} user={} authType={}",
                body != null ? body.host() : null,
                body != null ? body.sshPort() : null,
                body != null ? body.sshUser() : null,
                body != null ? body.authType() : null);
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        if (body.authType() == null || body.authType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authType is required");
        }
        return provisioningService.preflight(
                new DbProvisioningService.PreflightProvisioningCommand(
                        body.host(),
                        body.sshPort() != null ? body.sshPort() : 22,
                        body.sshUser(),
                        body.authType(),
                        body.sshPassword(),
                        body.privateKeyPem(),
                        body.privateKeyPassphrase()));
    }

    @PostMapping("/{id}/retry")
    public DbProvisioningService.ProvisioningDetail retry(
            @PathVariable String id,
            @RequestBody RetryProvisioningDto body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        if (body.authType() == null || body.authType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "authType is required");
        }
        return provisioningService.retryFailed(
                id,
                new DbProvisioningService.RetryProvisioningCommand(
                        body.authType(),
                        body.sshPassword(),
                        body.privateKeyPem(),
                        body.privateKeyPassphrase(),
                        body.dbOwnerPassword()));
    }

    @PostMapping
    public DbProvisioningService.ProvisioningDetail create(@RequestBody CreateProvisioningDto body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body is required");
        }
        return provisioningService.createAndRun(new DbProvisioningService.CreateProvisioningCommand(
                body.title(),
                body.host(),
                body.sshPort() != null ? body.sshPort() : 22,
                body.sshUser(),
                body.authType(),
                body.sshPassword(),
                body.privateKeyPem(),
                body.privateKeyPassphrase(),
                body.databaseName(),
                body.schemaName(),
                body.dbOwnerUser(),
                body.dbOwnerPassword(),
                body.pgMajorVersion() != null ? body.pgMajorVersion() : 18,
                body.osFamily(),
                body.osVersionLabel(),
                body.memoryMb() != null ? body.memoryMb() : 512,
                body.diskGb() != null ? body.diskGb() : 10,
                body.dataDirectory(),
                body.extensions()));
    }

    public record PreflightProvisioningDto(
            String host,
            Integer sshPort,
            String sshUser,
            String authType,
            String sshPassword,
            String privateKeyPem,
            String privateKeyPassphrase) {
    }

    public record RetryProvisioningDto(
            String authType,
            String sshPassword,
            String privateKeyPem,
            String privateKeyPassphrase,
            String dbOwnerPassword) {
    }

    public record CreateProvisioningDto(
            String title,
            String host,
            Integer sshPort,
            String sshUser,
            String authType,
            String sshPassword,
            String privateKeyPem,
            String privateKeyPassphrase,
            String databaseName,
            String schemaName,
            String dbOwnerUser,
            String dbOwnerPassword,
            Integer pgMajorVersion,
            String osFamily,
            String osVersionLabel,
            Integer memoryMb,
            Integer diskGb,
            String dataDirectory,
            List<String> extensions) {
    }
}
