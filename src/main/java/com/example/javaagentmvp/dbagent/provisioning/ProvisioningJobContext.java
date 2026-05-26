package com.example.javaagentmvp.dbagent.provisioning;

import java.util.List;

public record ProvisioningJobContext(
        String requestId,
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
        int memoryMb,
        int diskGb,
        String dataDirectory,
        List<String> extensions) {
}
