package com.example.javaagentmvp.dbagent.provisioning;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ProvisioningRequestValidator {

    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]*$");
    private static final Set<String> EXTENSIONS = Set.of("pg_stat_statements", "auto_explain", "pg_profile");

    private ProvisioningRequestValidator() {
    }

    public static void validateCreate(
            String host,
            int sshPort,
            String sshUser,
            String authType,
            String sshPassword,
            String privateKeyPem,
            String databaseName,
            String schemaName,
            int pgMajorVersion,
            String osFamily,
            int memoryMb,
            int diskGb,
            List<String> extensions) {
        if (host == null || host.isBlank()) {
            throw badRequest("host is required");
        }
        if (sshPort < 1 || sshPort > 65535) {
            throw badRequest("sshPort must be between 1 and 65535");
        }
        if (sshUser == null || sshUser.isBlank()) {
            throw badRequest("sshUser is required");
        }
        if (authType == null || (!authType.equals("PASSWORD") && !authType.equals("PRIVATE_KEY"))) {
            throw badRequest("authType must be PASSWORD or PRIVATE_KEY");
        }
        if ("PASSWORD".equals(authType) && (sshPassword == null || sshPassword.isBlank())) {
            throw badRequest("sshPassword is required for PASSWORD auth");
        }
        if ("PRIVATE_KEY".equals(authType) && (privateKeyPem == null || privateKeyPem.isBlank())) {
            throw badRequest("privateKeyPem is required for PRIVATE_KEY auth");
        }
        requireIdent(databaseName, "databaseName");
        requireIdent(schemaName, "schemaName");
        if (pgMajorVersion < 15 || pgMajorVersion > 18) {
            throw badRequest("pgMajorVersion must be between 15 and 18");
        }
        if (osFamily == null || osFamily.isBlank()) {
            throw badRequest("osFamily is required — run Check target first");
        }
        if (!ProvisioningOsCatalog.isPgInstallable(osFamily, pgMajorVersion)) {
            throw badRequest("PostgreSQL " + pgMajorVersion + " is not installable on " + osFamily);
        }
        if (memoryMb < 256 || memoryMb > 1_048_576) {
            throw badRequest("memoryMb must be between 256 and 1048576");
        }
        if (diskGb < 1 || diskGb > 1_048_576) {
            throw badRequest("diskGb must be between 1 and 1048576");
        }
        if (extensions != null) {
            for (String ext : extensions) {
                if (!EXTENSIONS.contains(ext)) {
                    throw badRequest("Unsupported extension: " + ext);
                }
            }
        }
    }

    private static void requireIdent(String value, String field) {
        if (value == null || !IDENT.matcher(value).matches()) {
            throw badRequest(field + " must match ^[a-z][a-z0-9_]*$");
        }
    }

    public static void validatePreflightCredentials(
            String host,
            int sshPort,
            String sshUser,
            String authType,
            String sshPassword,
            String privateKeyPem) {
        if (host == null || host.isBlank()) {
            throw badRequest("host is required");
        }
        if (sshPort < 1 || sshPort > 65535) {
            throw badRequest("sshPort must be between 1 and 65535");
        }
        if (sshUser == null || sshUser.isBlank()) {
            throw badRequest("sshUser is required");
        }
        if (authType == null || (!authType.equals("PASSWORD") && !authType.equals("PRIVATE_KEY"))) {
            throw badRequest("authType must be PASSWORD or PRIVATE_KEY");
        }
        if ("PASSWORD".equals(authType) && (sshPassword == null || sshPassword.isBlank())) {
            throw badRequest("sshPassword is required for PASSWORD auth");
        }
        if ("PRIVATE_KEY".equals(authType) && (privateKeyPem == null || privateKeyPem.isBlank())) {
            throw badRequest("privateKeyPem is required for PRIVATE_KEY auth");
        }
    }

    public static void validateRetryCredentials(String authType, String sshPassword, String privateKeyPem) {
        if (authType == null || (!authType.equals("PASSWORD") && !authType.equals("PRIVATE_KEY"))) {
            throw badRequest("authType must be PASSWORD or PRIVATE_KEY");
        }
        if ("PASSWORD".equals(authType) && (sshPassword == null || sshPassword.isBlank())) {
            throw badRequest("sshPassword is required for retry with PASSWORD auth");
        }
        if ("PRIVATE_KEY".equals(authType) && (privateKeyPem == null || privateKeyPem.isBlank())) {
            throw badRequest("privateKeyPem is required for retry with PRIVATE_KEY auth");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
