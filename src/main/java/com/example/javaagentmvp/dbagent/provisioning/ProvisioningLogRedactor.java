package com.example.javaagentmvp.dbagent.provisioning;

import java.util.regex.Pattern;

public final class ProvisioningLogRedactor {

    private static final Pattern PEM = Pattern.compile("-----BEGIN[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z ]*PRIVATE KEY-----");
    private static final Pattern PASSWORD_JSON = Pattern.compile("(\"(?:password|dbOwnerPassword|sshPassword|privateKeyPem)\"\\s*:\\s*\")([^\"]*)(\")", Pattern.CASE_INSENSITIVE);

    private ProvisioningLogRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = PEM.matcher(text).replaceAll("-----REDACTED PRIVATE KEY-----");
        out = PASSWORD_JSON.matcher(out).replaceAll("$1***$3");
        return out;
    }
}
