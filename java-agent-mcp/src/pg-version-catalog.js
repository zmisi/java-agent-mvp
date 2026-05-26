/** PG major versions installable per detected osFamily (PGDG). */
export const PG_BY_OS = {
  ubuntu: [18, 16, 15],
  rhel9: [18, 16, 15],
  rhel8: [18, 16, 15],
  rhel7: [],
};

export function listInstallablePgVersions(osFamily) {
  const versions = PG_BY_OS[osFamily];
  if (!versions || versions.length === 0) {
    return [];
  }
  return versions.map((major) => ({
    major,
    label: `PostgreSQL ${major}`,
    packageHint:
      osFamily === "ubuntu"
        ? `postgresql-${major}`
        : `postgresql${major}-server`,
  }));
}

export function isOsSupported(osFamily) {
  const versions = PG_BY_OS[osFamily];
  return Array.isArray(versions) && versions.length > 0;
}

export function osSupportMessage(osFamily, prettyName) {
  if (isOsSupported(osFamily)) {
    return null;
  }
  if (osFamily === "rhel7") {
    return `${prettyName || "RHEL/CentOS 7"} is not supported for PG provisioning. Use RHEL 8/9 or Ubuntu 22.04+.`;
  }
  return `OS '${osFamily}' is not supported for automated PostgreSQL installation.`;
}
