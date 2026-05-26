package com.example.javaagentmvp.dbagent.provisioning;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors java-agent-mcp/src/pg-version-catalog.js — keep in sync when changing supported OS/PG pairs.
 */
public final class ProvisioningOsCatalog {

    private static final Map<String, List<Integer>> PG_BY_OS = Map.of(
            "ubuntu", List.of(18, 16, 15),
            "rhel9", List.of(18, 16, 15),
            "rhel8", List.of(18, 16, 15),
            "rhel7", List.of());

    private ProvisioningOsCatalog() {
    }

    public static boolean isPgInstallable(String osFamily, int pgMajor) {
        if (osFamily == null || osFamily.isBlank()) {
            return false;
        }
        List<Integer> allowed = PG_BY_OS.get(osFamily);
        return allowed != null && allowed.contains(pgMajor);
    }

    public static Set<String> supportedOsFamilies() {
        return Set.of("ubuntu", "rhel8", "rhel9");
    }

    public static List<Integer> installablePgMajors(String osFamily) {
        return PG_BY_OS.getOrDefault(osFamily, List.of());
    }
}
