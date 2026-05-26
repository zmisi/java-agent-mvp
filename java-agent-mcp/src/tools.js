import { withSsh, runScript } from "./ssh.js";
import {
  listInstallablePgVersions,
  isOsSupported,
  osSupportMessage,
} from "./pg-version-catalog.js";

function jsonResult(payload) {
  return {
    content: [{ type: "text", text: JSON.stringify(payload) }],
  };
}

function errorResult(message, logExcerpt = "") {
  return jsonResult({
    ok: false,
    error: message,
    logExcerpt: logExcerpt || message,
  });
}

function logExcerpt(stdout, stderr, max = 2000) {
  const combined = [stdout, stderr].filter(Boolean).join("\n").trim();
  if (combined.length <= max) {
    return combined;
  }
  return combined.slice(0, max) + "\n…(truncated)";
}

function parseLastJsonLine(stdout) {
  const line = stdout.trim().split("\n").pop() || "";
  try {
    return JSON.parse(line);
  } catch {
    return {};
  }
}

export async function provisionPing(args) {
  try {
    const started = Date.now();
    await withSsh(args, async () => ({ ok: true }));
    return jsonResult({ ok: true, latencyMs: Date.now() - started });
  } catch (e) {
    return errorResult(e.message);
  }
}

function installScriptForOs(osFamily) {
  switch (osFamily) {
    case "ubuntu":
      return "install_pg_ubuntu.sh";
    case "rhel9":
      return "install_pg_rhel9.sh";
    case "rhel8":
      return "install_pg_rhel8.sh";
    default:
      return null;
  }
}

async function detectOsOnConn(conn) {
  const { stdout, stderr, exitCode } = await runScript(conn, "detect_os.sh");
  const parsed = parseLastJsonLine(stdout);
  const osFamily = parsed.osFamily || "unsupported";
  return {
    exitCode,
    osFamily,
    osId: parsed.osId || "",
    versionId: parsed.versionId || "",
    prettyName: parsed.prettyName || osFamily,
    logExcerpt: logExcerpt(stdout, stderr),
  };
}

function preflightLog(activityLog, phase, message, status = "done") {
  activityLog.push({
    phase,
    message,
    status,
    at: new Date().toISOString(),
  });
}

export async function provisionPreflight(args) {
  const activityLog = [];
  const host = args.host || "target";
  try {
    preflightLog(activityLog, "ssh", `Connecting to ${host}:${args.sshPort ?? 22} as ${args.sshUser}`, "running");
    return await withSsh(args, async (conn) => {
      const started = Date.now();
      preflightLog(activityLog, "ssh", "SSH session established", "done");

      preflightLog(activityLog, "detect_os", "Running detect_os.sh on target", "running");
      const os = await detectOsOnConn(conn);
      preflightLog(
        activityLog,
        "detect_os",
        `Detected: ${os.prettyName} (family=${os.osFamily}, id=${os.osId}, version=${os.versionId})`,
        os.exitCode === 0 ? "done" : "failed",
      );

      const supportMsg = osSupportMessage(os.osFamily, os.prettyName);
      const supported = isOsSupported(os.osFamily);
      const installableVersions = listInstallablePgVersions(os.osFamily);

      preflightLog(
        activityLog,
        "catalog",
        supported
          ? `Installable PG majors: ${installableVersions.map((v) => v.major).join(", ")}`
          : `No PG versions for OS family ${os.osFamily}`,
        supported ? "done" : "failed",
      );

      preflightLog(activityLog, "check_pg", "Checking existing PostgreSQL on target", "running");
      const check = await runScript(conn, "check_pg.sh", { PG_MAJOR: "0" });
      const pg = parseLastJsonLine(check.stdout);
      const installedMajor = Number(pg.majorVersion ?? 0);
      preflightLog(
        activityLog,
        "check_pg",
        installedMajor > 0
          ? `Found PostgreSQL major version ${installedMajor} on host`
          : "No PostgreSQL installation detected on host",
        check.exitCode === 0 ? "done" : "failed",
      );

      const combinedLog = [os.logExcerpt, logExcerpt(check.stdout, check.stderr)]
        .filter(Boolean)
        .join("\n---\n");

      return jsonResult({
        ok: supported && os.exitCode === 0,
        latencyMs: Date.now() - started,
        osFamily: os.osFamily,
        osId: os.osId,
        versionId: os.versionId,
        osVersionLabel: os.prettyName,
        supported,
        installableVersions,
        installedPgMajor: installedMajor > 0 ? installedMajor : null,
        error: supportMsg || (supported ? undefined : `Unsupported OS: ${os.osFamily}`),
        activityLog,
        logExcerpt: combinedLog || os.logExcerpt,
      });
    });
  } catch (e) {
    preflightLog(activityLog, "ssh", `SSH failed: ${e.message}`, "failed");
    return jsonResult({
      ok: false,
      error: e.message,
      activityLog,
      logExcerpt: e.message,
    });
  }
}

export async function provisionDetectOs(args) {
  try {
    return await withSsh(args, async (conn) => {
      const os = await detectOsOnConn(conn);
      const supportMsg = osSupportMessage(os.osFamily, os.prettyName);
      if (supportMsg) {
        return jsonResult({
          ok: false,
          osFamily: os.osFamily,
          osVersionLabel: os.prettyName,
          error: supportMsg,
          logExcerpt: os.logExcerpt,
        });
      }
      const ok = os.exitCode === 0 && isOsSupported(os.osFamily);
      return jsonResult({
        ok,
        osFamily: os.osFamily,
        osVersionLabel: os.prettyName,
        error: ok ? undefined : `Unsupported OS: ${os.osFamily}`,
        logExcerpt: os.logExcerpt,
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

export async function provisionCheckPg(args) {
  const pgMajor = Number(args.pgMajor ?? 18);
  try {
    return await withSsh(args, async (conn) => {
      const { stdout, stderr, exitCode } = await runScript(conn, "check_pg.sh", {
        PG_MAJOR: String(pgMajor),
      });
      const parsed = parseLastJsonLine(stdout);
      const majorVersion = Number(parsed.majorVersion ?? 0);
      const installed = parsed.installed === true;
      const skipped = installed && majorVersion >= pgMajor;
      const log = logExcerpt(stdout, stderr);
      return jsonResult({
        ok: exitCode === 0,
        installed,
        majorVersion,
        pgMajor,
        skipped,
        source: parsed.source,
        error: exitCode === 0 ? undefined : `check_pg.sh exited with code ${exitCode}`,
        logExcerpt:
          log ||
          `Checked PostgreSQL target major ${pgMajor}; detected major ${majorVersion || 0}.`,
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

export async function provisionInstallPg(args) {
  const pgMajor = Number(args.pgMajor ?? 18);
  try {
    const osFamily = args.osFamily || "ubuntu";
    const script = installScriptForOs(osFamily);
    if (!script) {
      return jsonResult({
        ok: false,
        error: `No install script for osFamily: ${osFamily}`,
        logExcerpt: "Supported: ubuntu, rhel8, rhel9",
      });
    }
    if (!isOsSupported(osFamily)) {
      return jsonResult({
        ok: false,
        error: osSupportMessage(osFamily, osFamily) || `Unsupported OS: ${osFamily}`,
        logExcerpt: "",
      });
    }
    const allowed = listInstallablePgVersions(osFamily).map((v) => v.major);
    if (!allowed.includes(pgMajor)) {
      return jsonResult({
        ok: false,
        error: `PostgreSQL ${pgMajor} is not installable on ${osFamily}`,
        logExcerpt: `Allowed: ${allowed.join(", ")}`,
      });
    }
    return await withSsh(args, async (conn) => {
      const check = await runScript(conn, "check_pg.sh", { PG_MAJOR: String(pgMajor) });
      const checkJson = parseLastJsonLine(check.stdout);
      if (checkJson.installed === true || Number(checkJson.majorVersion) >= pgMajor) {
        return jsonResult({
          ok: true,
          installed: true,
          skipped: true,
          pgMajor,
          logExcerpt: logExcerpt(check.stdout, check.stderr),
        });
      }
      const { stdout, stderr, exitCode } = await runScript(conn, script, {
        PG_MAJOR: String(pgMajor),
      });
      const combined = logExcerpt(stdout, stderr);
      if (exitCode !== 0) {
        return jsonResult({
          ok: false,
          installed: false,
          skipped: false,
          pgMajor,
          error: `install script exited with code ${exitCode}`,
          logExcerpt: combined,
        });
      }
      return jsonResult({
        ok: true,
        installed: true,
        skipped: false,
        pgMajor,
        logExcerpt: combined,
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

/** @deprecated use provision_check_pg */
export async function provisionCheckPg18(args) {
  return provisionCheckPg({ ...args, pgMajor: args.pgMajor ?? 18 });
}

/** @deprecated use provision_install_pg */
export async function provisionInstallPg18(args) {
  return provisionInstallPg({ ...args, pgMajor: args.pgMajor ?? 18 });
}

export async function provisionTuneMemory(args) {
  try {
    const memoryMb = Number(args.memoryMb ?? 0);
    const pgMajor = Number(args.pgMajor ?? 18);
    return await withSsh(args, async (conn) => {
      const { stdout, stderr, exitCode } = await runScript(conn, "tune_memory.sh", {
        MEMORY_MB: memoryMb,
        PG_MAJOR: String(pgMajor),
      });
      return jsonResult({
        ok: exitCode === 0,
        memoryMb,
        applied: exitCode === 0,
        logExcerpt: logExcerpt(stdout, stderr),
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

export async function provisionCheckDisk(args) {
  try {
    const diskGb = Number(args.diskGb ?? 0);
    const dataDirectory = args.dataDirectory || "";
    return await withSsh(args, async (conn) => {
      const { stdout, stderr, exitCode } = await runScript(conn, "check_disk.sh", {
        DISK_GB: diskGb,
        DATA_DIR: dataDirectory,
      });
      const parsed = parseLastJsonLine(stdout);
      return jsonResult({
        ok: exitCode === 0 && parsed.ok !== false,
        diskGb,
        availableGb: parsed.availableGb ?? 0,
        logExcerpt: logExcerpt(stdout, stderr),
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

export async function provisionCreateDatabase(args) {
  try {
    return await withSsh(args, async (conn) => {
      const { stdout, stderr, exitCode } = await runScript(conn, "create_db.sh", {
        DB_NAME: args.databaseName,
        SCHEMA_NAME: args.schemaName,
        OWNER: args.dbOwnerUser || `${args.databaseName}_owner`,
        OWNER_PASSWORD: args.dbOwnerPassword || "",
        PG_MAJOR: String(args.pgMajor ?? 18),
        SERVER_HOST: args.host || "127.0.0.1",
      });
      const parsed = parseLastJsonLine(stdout);
      return jsonResult({
        ok: exitCode === 0,
        database: args.databaseName,
        schema: args.schemaName,
        owner: parsed.owner,
        connectionHint: parsed.connectionHint,
        logExcerpt: logExcerpt(stdout, stderr),
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

export async function provisionInstallExtension(args) {
  try {
    const extension = args.extension;
    return await withSsh(args, async (conn) => {
      const pgMajor = Number(args.pgMajor ?? 18);
      const { stdout, stderr, exitCode } = await runScript(conn, "install_extension.sh", {
        EXT_NAME: extension,
        DB_NAME: args.databaseName,
        PG_MAJOR: String(pgMajor),
      });
      const parsed = parseLastJsonLine(stdout);
      return jsonResult({
        ok: exitCode === 0,
        extension,
        skipped: parsed.skipped === true,
        installed: parsed.installed === true,
        logExcerpt: logExcerpt(stdout, stderr),
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

export async function provisionVerify(args) {
  try {
    return await withSsh(args, async (conn) => {
      const { stdout, stderr, exitCode } = await runScript(conn, "verify_db.sh", {
        DB_NAME: args.databaseName,
        OWNER: args.dbOwnerUser || `${args.databaseName}_owner`,
        OWNER_PASSWORD: args.dbOwnerPassword || "",
      });
      const parsed = parseLastJsonLine(stdout);
      return jsonResult({
        ok: exitCode === 0 && parsed.ok === true,
        error: parsed.error,
        serverVersion: parsed.serverVersion,
        logExcerpt: logExcerpt(stdout, stderr),
      });
    });
  } catch (e) {
    return errorResult(e.message);
  }
}

function connectionSchema() {
  return {
    type: "object",
    properties: {
      host: { type: "string" },
      sshPort: { type: "number" },
      sshUser: { type: "string" },
      authType: { type: "string", enum: ["PASSWORD", "PRIVATE_KEY"] },
      password: { type: "string" },
      privateKeyPem: { type: "string" },
      passphrase: { type: "string" },
    },
    required: ["host", "sshUser", "authType"],
  };
}

const connProps = connectionSchema().properties;
const connRequired = connectionSchema().required;

export const TOOL_DEFINITIONS = [
  { name: "provision_ping", description: "Test SSH connectivity", inputSchema: connectionSchema() },
  {
    name: "provision_preflight",
    description: "Detect OS and list installable PostgreSQL versions",
    inputSchema: connectionSchema(),
  },
  { name: "provision_detect_os", description: "Detect OS family", inputSchema: connectionSchema() },
  {
    name: "provision_check_pg",
    description: "Check PostgreSQL major version on target",
    inputSchema: {
      type: "object",
      properties: { ...connProps, pgMajor: { type: "number" } },
      required: [...connRequired, "pgMajor"],
    },
  },
  {
    name: "provision_install_pg",
    description: "Install PostgreSQL major version",
    inputSchema: {
      type: "object",
      properties: { ...connProps, osFamily: { type: "string" }, pgMajor: { type: "number" } },
      required: [...connRequired, "pgMajor"],
    },
  },
  {
    name: "provision_check_pg18",
    description: "Check PostgreSQL 18 (alias)",
    inputSchema: connectionSchema(),
  },
  {
    name: "provision_install_pg18",
    description: "Install PostgreSQL 18 (alias)",
    inputSchema: {
      type: "object",
      properties: { ...connProps, osFamily: { type: "string" } },
      required: connRequired,
    },
  },
  {
    name: "provision_tune_memory",
    description: "Tune PostgreSQL memory",
    inputSchema: {
      type: "object",
      properties: { ...connProps, memoryMb: { type: "number" }, pgMajor: { type: "number" } },
      required: [...connRequired, "memoryMb"],
    },
  },
  {
    name: "provision_check_disk",
    description: "Check disk space",
    inputSchema: {
      type: "object",
      properties: { ...connProps, diskGb: { type: "number" }, dataDirectory: { type: "string" } },
      required: [...connRequired, "diskGb"],
    },
  },
  {
    name: "provision_create_database",
    description: "Create database and schema",
    inputSchema: {
      type: "object",
      properties: {
        ...connProps,
        databaseName: { type: "string" },
        schemaName: { type: "string" },
        dbOwnerUser: { type: "string" },
        dbOwnerPassword: { type: "string" },
      },
      required: [...connRequired, "databaseName", "schemaName"],
    },
  },
  {
    name: "provision_install_extension",
    description: "Install PostgreSQL extension",
    inputSchema: {
      type: "object",
      properties: { ...connProps, extension: { type: "string" }, databaseName: { type: "string" } },
      required: [...connRequired, "extension", "databaseName"],
    },
  },
  {
    name: "provision_verify",
    description: "Verify database connection",
    inputSchema: {
      type: "object",
      properties: { ...connProps, databaseName: { type: "string" }, dbOwnerUser: { type: "string" } },
      required: [...connRequired, "databaseName"],
    },
  },
];

export const TOOL_HANDLERS = {
  provision_ping: provisionPing,
  provision_preflight: provisionPreflight,
  provision_detect_os: provisionDetectOs,
  provision_check_pg: provisionCheckPg,
  provision_install_pg: provisionInstallPg,
  provision_check_pg18: provisionCheckPg18,
  provision_install_pg18: provisionInstallPg18,
  provision_tune_memory: provisionTuneMemory,
  provision_check_disk: provisionCheckDisk,
  provision_create_database: provisionCreateDatabase,
  provision_install_extension: provisionInstallExtension,
  provision_verify: provisionVerify,
};
