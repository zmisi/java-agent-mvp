package com.example.javaagentmvp.dbagent.provisioning;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProvisioningStepRunner {

    private final ProvisioningMcpClient mcpClient;

    public ProvisioningStepRunner(ProvisioningMcpClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    public StepOutcome runStep(String stepName, ProvisioningJobContext ctx, String osFamilyHint) {
        Map<String, Object> base = new HashMap<>(mcpClient.connectionArgs(ctx));
        return switch (stepName) {
            case ProvisioningSteps.VALIDATE_INPUT -> StepOutcome.ok("Input validated");
            case ProvisioningSteps.SSH_CONNECT -> runPing(base);
            case ProvisioningSteps.DETECT_OS -> runDetectOs(base);
            case ProvisioningSteps.CHECK_PG_VERSION -> runCheckPg(base, ctx.pgMajorVersion());
            case ProvisioningSteps.INSTALL_PG18 -> runInstallPg(base, osFamilyHint, ctx.pgMajorVersion());
            case ProvisioningSteps.TUNE_MEMORY -> runTuneMemory(base, ctx.memoryMb(), ctx.pgMajorVersion());
            case ProvisioningSteps.CHECK_DISK -> runCheckDisk(base, ctx);
            case ProvisioningSteps.CREATE_DATABASE -> runCreateDb(base, ctx);
            case ProvisioningSteps.INSTALL_EXTENSIONS -> runExtensions(base, ctx);
            case ProvisioningSteps.VERIFY_CONNECTION -> runVerify(base, ctx);
            case ProvisioningSteps.COMPLETE -> StepOutcome.ok("Provisioning complete");
            default -> StepOutcome.fail("Unknown step: " + stepName);
        };
    }

    private StepOutcome runPing(Map<String, Object> base) {
        Map<String, Object> r = mcpClient.callTool("provision_ping", base);
        return outcomeFrom(r, "SSH connection failed");
    }

    private StepOutcome runDetectOs(Map<String, Object> base) {
        Map<String, Object> r = mcpClient.callTool("provision_detect_os", base);
        if (!bool(r, "ok")) {
            return StepOutcome.fail(logExcerpt(r, "Unsupported or unknown OS"));
        }
        String osFamily = r.get("osFamily") != null ? r.get("osFamily").toString() : null;
        return StepOutcome.ok(logExcerpt(r, "Detected OS: " + osFamily), null, osFamily);
    }

    private StepOutcome runCheckPg(Map<String, Object> base, int pgMajor) {
        base.put("pgMajor", pgMajor);
        Map<String, Object> r = mcpClient.callTool("provision_check_pg", base);
        if (bool(r, "skipped")) {
            return StepOutcome.skipped(logExcerpt(r, "PostgreSQL " + pgMajor + " already installed"));
        }
        if (bool(r, "ok")) {
            Object major = r.get("majorVersion");
            String detected = major != null ? major.toString() : "0";
            return StepOutcome.ok(logExcerpt(
                    r,
                    "PostgreSQL " + pgMajor + " not installed yet (detected major: " + detected + "); install step will run next"));
        }
        return StepOutcome.fail(logExcerpt(r, "PostgreSQL version check failed"));
    }

    private StepOutcome runInstallPg(Map<String, Object> base, String osFamily, int pgMajor) {
        if (osFamily == null || osFamily.isBlank()) {
            return StepOutcome.fail("OS family not detected");
        }
        base.put("osFamily", osFamily);
        base.put("pgMajor", pgMajor);
        Map<String, Object> r = mcpClient.callTool("provision_install_pg", base);
        if (bool(r, "skipped")) {
            return StepOutcome.skipped(logExcerpt(r, "Skipped PostgreSQL " + pgMajor + " install"));
        }
        return outcomeFrom(r, "PostgreSQL " + pgMajor + " installation failed");
    }

    private StepOutcome runTuneMemory(Map<String, Object> base, int memoryMb, int pgMajor) {
        base.put("memoryMb", memoryMb);
        base.put("pgMajor", pgMajor);
        Map<String, Object> r = mcpClient.callTool("provision_tune_memory", base);
        return outcomeFrom(r, "Memory tuning failed");
    }

    private StepOutcome runCheckDisk(Map<String, Object> base, ProvisioningJobContext ctx) {
        base.put("diskGb", ctx.diskGb());
        if (ctx.dataDirectory() != null) {
            base.put("dataDirectory", ctx.dataDirectory());
        }
        Map<String, Object> r = mcpClient.callTool("provision_check_disk", base);
        return outcomeFrom(r, "Insufficient disk space");
    }

    private StepOutcome runCreateDb(Map<String, Object> base, ProvisioningJobContext ctx) {
        base.put("databaseName", ctx.databaseName());
        base.put("schemaName", ctx.schemaName());
        String owner = ctx.dbOwnerUser() != null && !ctx.dbOwnerUser().isBlank()
                ? ctx.dbOwnerUser()
                : ctx.databaseName() + "_owner";
        base.put("dbOwnerUser", owner);
        if (ctx.dbOwnerPassword() != null && !ctx.dbOwnerPassword().isBlank()) {
            base.put("dbOwnerPassword", ctx.dbOwnerPassword());
        }
        base.put("pgMajor", ctx.pgMajorVersion());
        Map<String, Object> r = mcpClient.callTool("provision_create_database", base);
        if (!bool(r, "ok")) {
            return StepOutcome.fail(logExcerpt(r, "Database creation failed"));
        }
        String hint = r.get("connectionHint") != null ? r.get("connectionHint").toString() : null;
        return StepOutcome.okWithHint(logExcerpt(r, "Database created"), hint);
    }

    private StepOutcome runExtensions(Map<String, Object> base, ProvisioningJobContext ctx) {
        List<String> extensions = ctx.extensions();
        if (extensions == null || extensions.isEmpty()) {
            return StepOutcome.skipped("No extensions selected");
        }
        StringBuilder log = new StringBuilder();
        for (String ext : extensions) {
            Map<String, Object> args = new HashMap<>(base);
            args.put("extension", ext);
            args.put("databaseName", ctx.databaseName());
            args.put("pgMajor", ctx.pgMajorVersion());
            Map<String, Object> r = mcpClient.callTool("provision_install_extension", args);
            log.append(ext).append(": ");
            if (bool(r, "skipped")) {
                log.append("skipped\n");
            } else if (bool(r, "ok")) {
                log.append("installed\n");
            } else {
                log.append("failed — ").append(logExcerpt(r, "")).append("\n");
                return StepOutcome.fail(ProvisioningLogRedactor.redact(log.toString()));
            }
            log.append(logExcerpt(r, "")).append("\n");
        }
        return StepOutcome.ok(ProvisioningLogRedactor.redact(log.toString()));
    }

    private StepOutcome runVerify(Map<String, Object> base, ProvisioningJobContext ctx) {
        base.put("databaseName", ctx.databaseName());
        String owner = ctx.dbOwnerUser() != null && !ctx.dbOwnerUser().isBlank()
                ? ctx.dbOwnerUser()
                : ctx.databaseName() + "_owner";
        base.put("dbOwnerUser", owner);
        if (ctx.dbOwnerPassword() != null && !ctx.dbOwnerPassword().isBlank()) {
            base.put("dbOwnerPassword", ctx.dbOwnerPassword());
        }
        Map<String, Object> r = mcpClient.callTool("provision_verify", base);
        return outcomeFrom(r, "Connection verification failed");
    }

    private static StepOutcome outcomeFrom(Map<String, Object> r, String failMessage) {
        if (bool(r, "ok")) {
            return StepOutcome.ok(logExcerpt(r, "OK"));
        }
        return StepOutcome.fail(logExcerpt(r, failMessage));
    }

    private static boolean bool(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }

    private static String logExcerpt(Map<String, Object> r, String fallback) {
        Object log = r.get("logExcerpt");
        Object err = r.get("error");
        StringBuilder sb = new StringBuilder();
        if (err != null && !err.toString().isBlank()) {
            sb.append(ProvisioningLogRedactor.redact(err.toString()));
        }
        if (log != null && !log.toString().isBlank()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(ProvisioningLogRedactor.redact(log.toString()));
        }
        if (!sb.isEmpty()) {
            return sb.toString();
        }
        return fallback;
    }

    public record StepOutcome(
            ProvisioningStepStatus status,
            String log,
            String connectionHint,
            String osFamily) {

        static StepOutcome ok(String log) {
            return new StepOutcome(ProvisioningStepStatus.SUCCEEDED, log, null, null);
        }

        static StepOutcome ok(String log, String connectionHint, String osFamily) {
            return new StepOutcome(ProvisioningStepStatus.SUCCEEDED, log, connectionHint, osFamily);
        }

        static StepOutcome okWithHint(String log, String hint) {
            return new StepOutcome(ProvisioningStepStatus.SUCCEEDED, log, hint, null);
        }

        static StepOutcome skipped(String log) {
            return new StepOutcome(ProvisioningStepStatus.SKIPPED, log, null, null);
        }

        static StepOutcome fail(String log) {
            return new StepOutcome(ProvisioningStepStatus.FAILED, log, null, null);
        }
    }
}
