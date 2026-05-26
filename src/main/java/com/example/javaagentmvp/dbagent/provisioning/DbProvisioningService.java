package com.example.javaagentmvp.dbagent.provisioning;

import com.example.javaagentmvp.dbagent.provisioning.persistence.mapper.ProvisioningMapper;
import com.example.javaagentmvp.dbagent.provisioning.persistence.model.ProvisioningRequestRow;
import com.example.javaagentmvp.dbagent.provisioning.persistence.model.ProvisioningStepRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DbProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(DbProvisioningService.class);

    private final ProvisioningMapper provisioningMapper;
    private final ProvisioningJobExecutor jobExecutor;
    private final ProvisioningMcpClient mcpClient;
    private final ObjectMapper objectMapper;

    public DbProvisioningService(
            ProvisioningMapper provisioningMapper,
            ProvisioningJobExecutor jobExecutor,
            ProvisioningMcpClient mcpClient,
            ObjectMapper objectMapper) {
        this.provisioningMapper = provisioningMapper;
        this.jobExecutor = jobExecutor;
        this.mcpClient = mcpClient;
        this.objectMapper = objectMapper;
    }

    public PreflightResult preflight(PreflightProvisioningCommand cmd) {
        ProvisioningRequestValidator.validatePreflightCredentials(
                cmd.host(),
                cmd.sshPort(),
                cmd.sshUser(),
                cmd.authType(),
                cmd.sshPassword(),
                cmd.privateKeyPem());

        List<PreflightActivityEntry> activityLog = new ArrayList<>();
        activityLog.add(entry("validate", "Validated SSH connection parameters", "done"));
        log.info(
                "Preflight start host={} port={} user={} authType={}",
                cmd.host(),
                cmd.sshPort(),
                cmd.sshUser(),
                cmd.authType());

        try {
            activityLog.add(entry(
                    "mcp",
                    "Invoking provision_preflight (starts MCP on first use; may take 30–90s)",
                    "running"));
            Map<String, Object> args = mcpClient.connectionArgs(cmd);
            Map<String, Object> r = mcpClient.callTool("provision_preflight", args);
            activityLog.add(entry("mcp", "MCP provision_preflight completed", "done"));
            log.info(
                    "Preflight MCP done host={} ok={} osFamily={}",
                    cmd.host(),
                    r.get("ok"),
                    r.get("osFamily"));
            return toPreflightResult(r, activityLog);
        }
        catch (Exception ex) {
            log.warn("Preflight failed host={}: {}", cmd.host(), ex.getMessage());
            activityLog.add(entry("mcp", "Preflight failed: " + ex.getMessage(), "failed"));
            return new PreflightResult(
                    false,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    null,
                    ex.getMessage(),
                    ex.getMessage(),
                    activityLog);
        }
    }

    private static PreflightActivityEntry entry(String phase, String message, String status) {
        return new PreflightActivityEntry(phase, message, status, Instant.now().toString());
    }

    public List<ProvisioningSummary> list() {
        return provisioningMapper.listRequests().stream()
                .map(this::toSummary)
                .toList();
    }

    public ProvisioningDetail get(String id) {
        ProvisioningRequestRow row = requireRequest(id);
        List<ProvisioningStepView> steps = provisioningMapper.listStepsByRequestId(id).stream()
                .map(this::toStepView)
                .toList();
        return toDetail(row, steps);
    }

    @Transactional
    public ProvisioningDetail createAndRun(CreateProvisioningCommand cmd) {
        ProvisioningRequestValidator.validateCreate(
                cmd.host(),
                cmd.sshPort(),
                cmd.sshUser(),
                cmd.authType(),
                cmd.sshPassword(),
                cmd.privateKeyPem(),
                cmd.databaseName(),
                cmd.schemaName(),
                cmd.pgMajorVersion(),
                cmd.osFamily(),
                cmd.memoryMb(),
                cmd.diskGb(),
                cmd.extensions());

        String id = newRequestId();
        Instant now = Instant.now();
        String title = cmd.title() != null && !cmd.title().isBlank()
                ? cmd.title().strip()
                : cmd.databaseName() + "@" + cmd.host();

        ProvisioningRequestRow request = new ProvisioningRequestRow();
        request.setId(id);
        request.setTitle(title);
        request.setHost(cmd.host().strip());
        request.setSshPort(cmd.sshPort());
        request.setSshUser(cmd.sshUser().strip());
        request.setAuthType(cmd.authType());
        request.setDatabaseName(cmd.databaseName());
        request.setSchemaName(cmd.schemaName());
        request.setMemoryMb(cmd.memoryMb());
        request.setDiskGb(cmd.diskGb());
        request.setDataDirectory(cmd.dataDirectory());
        request.setExtensions(toExtensionsJson(cmd.extensions()));
        request.setDbOwnerUser(cmd.dbOwnerUser());
        request.setPgMajorVersion(cmd.pgMajorVersion());
        request.setOsFamily(cmd.osFamily());
        request.setOsVersionLabel(cmd.osVersionLabel());
        request.setStatus(ProvisioningStatus.RUNNING.name());
        request.setCreatedAt(now.toString());
        request.setUpdatedAt(now.toString());
        provisioningMapper.insertRequest(request);

        int order = 0;
        for (String stepName : ProvisioningSteps.ORDER) {
            ProvisioningStepRow step = new ProvisioningStepRow();
            step.setRequestId(id);
            step.setStepName(stepName);
            step.setStatus(ProvisioningStepStatus.PENDING.name());
            step.setSortOrder(order++);
            provisioningMapper.insertStep(step);
        }

        ProvisioningJobContext ctx = buildJobContext(requireRequest(id), cmd);
        jobExecutor.execute(ctx);
        return get(id);
    }

    @Transactional
    public ProvisioningDetail retryFailed(String id, RetryProvisioningCommand cmd) {
        ProvisioningRequestRow row = requireRequest(id);
        String status = row.getStatus();
        if (ProvisioningStatus.RUNNING.name().equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Provisioning is still running");
        }
        if (!ProvisioningStatus.FAILED.name().equals(status)
                && !ProvisioningStatus.CANCELLED.name().equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only failed or cancelled jobs can be retried (current: " + status + ")");
        }

        ProvisioningRequestValidator.validateRetryCredentials(
                cmd.authType(), cmd.sshPassword(), cmd.privateKeyPem());

        Instant now = Instant.now();
        provisioningMapper.resetStepsForRequest(id);

        ProvisioningRequestRow update = new ProvisioningRequestRow();
        update.setId(id);
        update.setStatus(ProvisioningStatus.RUNNING.name());
        update.setErrorSummary(null);
        update.setConnectionHint(null);
        update.setUpdatedAt(now.toString());
        provisioningMapper.updateRequestStatus(update);

        ProvisioningJobContext ctx = buildJobContext(row, cmd);
        jobExecutor.execute(ctx);
        return get(id);
    }

    private ProvisioningJobContext buildJobContext(ProvisioningRequestRow row, RetryProvisioningCommand cmd) {
        String owner = row.getDbOwnerUser() != null && !row.getDbOwnerUser().isBlank()
                ? row.getDbOwnerUser()
                : row.getDatabaseName() + "_owner";
        String dbPassword = cmd.dbOwnerPassword() != null && !cmd.dbOwnerPassword().isBlank()
                ? cmd.dbOwnerPassword()
                : null;
        return new ProvisioningJobContext(
                row.getId(),
                row.getHost(),
                row.getSshPort(),
                row.getSshUser(),
                cmd.authType(),
                cmd.sshPassword(),
                cmd.privateKeyPem(),
                cmd.privateKeyPassphrase(),
                row.getDatabaseName(),
                row.getSchemaName(),
                owner,
                dbPassword,
                row.getPgMajorVersion() > 0 ? row.getPgMajorVersion() : 18,
                row.getMemoryMb(),
                row.getDiskGb(),
                row.getDataDirectory(),
                parseExtensions(row.getExtensions()));
    }

    private ProvisioningJobContext buildJobContext(ProvisioningRequestRow row, CreateProvisioningCommand cmd) {
        String owner = row.getDbOwnerUser() != null && !row.getDbOwnerUser().isBlank()
                ? row.getDbOwnerUser()
                : row.getDatabaseName() + "_owner";
        return new ProvisioningJobContext(
                row.getId(),
                row.getHost(),
                row.getSshPort(),
                row.getSshUser(),
                cmd.authType(),
                cmd.sshPassword(),
                cmd.privateKeyPem(),
                cmd.privateKeyPassphrase(),
                row.getDatabaseName(),
                row.getSchemaName(),
                owner,
                cmd.dbOwnerPassword(),
                row.getPgMajorVersion() > 0 ? row.getPgMajorVersion() : 18,
                row.getMemoryMb(),
                row.getDiskGb(),
                row.getDataDirectory(),
                parseExtensions(row.getExtensions()));
    }

    private String toExtensionsJson(List<String> extensions) {
        try {
            return objectMapper.writeValueAsString(extensions != null ? extensions : List.of());
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize extensions", ex);
        }
    }

    private ProvisioningRequestRow requireRequest(String id) {
        ProvisioningRequestRow row = provisioningMapper.selectRequestById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provisioning request not found: " + id);
        }
        return row;
    }

    private String newRequestId() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        return "PRV-" + date + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    private ProvisioningSummary toSummary(ProvisioningRequestRow row) {
        return new ProvisioningSummary(
                row.getId(),
                row.getTitle(),
                row.getHost(),
                row.getDatabaseName(),
                row.getStatus(),
                row.getUpdatedAt());
    }

    private ProvisioningDetail toDetail(ProvisioningRequestRow row, List<ProvisioningStepView> steps) {
        List<String> extensions = parseExtensions(row.getExtensions());
        return new ProvisioningDetail(
                row.getId(),
                row.getTitle(),
                row.getHost(),
                row.getSshPort(),
                row.getSshUser(),
                row.getAuthType(),
                row.getDatabaseName(),
                row.getSchemaName(),
                row.getMemoryMb(),
                row.getDiskGb(),
                row.getDataDirectory(),
                extensions,
                row.getDbOwnerUser(),
                row.getPgMajorVersion(),
                row.getOsFamily(),
                row.getOsVersionLabel(),
                row.getStatus(),
                row.getErrorSummary(),
                row.getConnectionHint(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                steps);
    }

    private List<String> parseExtensions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private ProvisioningStepView toStepView(ProvisioningStepRow row) {
        return new ProvisioningStepView(
                row.getStepName(),
                row.getStatus(),
                row.getLogText(),
                row.getStartedAt(),
                row.getFinishedAt(),
                row.getSortOrder());
    }

    public record RetryProvisioningCommand(
            String authType,
            String sshPassword,
            String privateKeyPem,
            String privateKeyPassphrase,
            String dbOwnerPassword) {
    }

    @SuppressWarnings("unchecked")
    private PreflightResult toPreflightResult(Map<String, Object> r, List<PreflightActivityEntry> serverLog) {
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        String osFamily = stringVal(r.get("osFamily"));
        List<PreflightActivityEntry> activityLog = new ArrayList<>(serverLog);
        activityLog.addAll(parseActivityLog(r.get("activityLog")));
        List<InstallablePgVersion> versions = new ArrayList<>();
        Object rawVersions = r.get("installableVersions");
        if (rawVersions instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    int major = numberVal(m.get("major"), 0);
                    String label = stringVal(m.get("label"));
                    String hint = stringVal(m.get("packageHint"));
                    if (major > 0) {
                        versions.add(new InstallablePgVersion(major, label, hint));
                    }
                }
            }
        }
        Integer installed = null;
        Object installedRaw = r.get("installedPgMajor");
        if (installedRaw instanceof Number n) {
            installed = n.intValue();
        }
        String error = stringVal(r.get("error"));
        if (!ok && (error == null || error.isBlank())) {
            error = "Target preflight failed";
        }
        return new PreflightResult(
                ok,
                osFamily,
                stringVal(r.get("osVersionLabel")),
                stringVal(r.get("osId")),
                stringVal(r.get("versionId")),
                versions,
                installed,
                error,
                stringVal(r.get("logExcerpt")),
                activityLog);
    }

    @SuppressWarnings("unchecked")
    private List<PreflightActivityEntry> parseActivityLog(Object raw) {
        List<PreflightActivityEntry> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(new PreflightActivityEntry(
                        stringVal(m.get("phase")),
                        stringVal(m.get("message")),
                        stringVal(m.get("status")),
                        stringVal(m.get("at"))));
            }
        }
        return out;
    }

    private static String stringVal(Object v) {
        return v != null ? v.toString() : null;
    }

    private static int numberVal(Object v, int fallback) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public record PreflightProvisioningCommand(
            String host,
            int sshPort,
            String sshUser,
            String authType,
            String sshPassword,
            String privateKeyPem,
            String privateKeyPassphrase) {
    }

    public record InstallablePgVersion(int major, String label, String packageHint) {
    }

    public record PreflightActivityEntry(String phase, String message, String status, String at) {
    }

    public record PreflightResult(
            boolean ok,
            String osFamily,
            String osVersionLabel,
            String osId,
            String versionId,
            List<InstallablePgVersion> installableVersions,
            Integer installedPgMajor,
            String error,
            String logExcerpt,
            List<PreflightActivityEntry> activityLog) {
    }

    public record CreateProvisioningCommand(
            String title,
            String host,
            int sshPort,
            String sshUser,
            String authType,
            String sshPassword,
            String privateKeyPem,
            String privateKeyPassphrase,
            String databaseName,
            String schemaName,
            String dbOwnerUser,
            String dbOwnerPassword,
            int pgMajorVersion,
            String osFamily,
            String osVersionLabel,
            int memoryMb,
            int diskGb,
            String dataDirectory,
            List<String> extensions) {
    }

    public record ProvisioningSummary(
            String id,
            String title,
            String host,
            String databaseName,
            String status,
            String updatedAt) {
    }

    public record ProvisioningStepView(
            String stepName,
            String status,
            String logText,
            String startedAt,
            String finishedAt,
            int sortOrder) {
    }

    public record ProvisioningDetail(
            String id,
            String title,
            String host,
            int sshPort,
            String sshUser,
            String authType,
            String databaseName,
            String schemaName,
            int memoryMb,
            int diskGb,
            String dataDirectory,
            List<String> extensions,
            String dbOwnerUser,
            int pgMajorVersion,
            String osFamily,
            String osVersionLabel,
            String status,
            String errorSummary,
            String connectionHint,
            String createdAt,
            String updatedAt,
            List<ProvisioningStepView> steps) {
    }
}
