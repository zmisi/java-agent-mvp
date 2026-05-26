import { Client } from "ssh2";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const SCRIPTS_ROOT = join(__dirname, "..", "scripts", "provisioning");

/**
 * @typedef {object} SshConnectionParams
 * @property {string} host
 * @property {number} sshPort
 * @property {string} sshUser
 * @property {'PASSWORD'|'PRIVATE_KEY'} authType
 * @property {string} [password]
 * @property {string} [privateKeyPem]
 * @property {string} [passphrase]
 */

export function connectSsh(params) {
  return new Promise((resolve, reject) => {
    const conn = new Client();
    const config = {
      host: params.host,
      port: params.sshPort ?? 22,
      username: params.sshUser,
      readyTimeout: 30_000,
    };
    if (params.authType === "PRIVATE_KEY") {
      config.privateKey = params.privateKeyPem;
      if (params.passphrase) {
        config.passphrase = params.passphrase;
      }
    } else {
      config.password = params.password;
    }
    conn
      .on("ready", () => resolve(conn))
      .on("error", reject)
      .connect(config);
  });
}

/**
 * @param {number} [timeoutMs] 0 or omitted = no command timeout (for long installs).
 */
export function execCommand(conn, command, timeoutMs = 0) {
  return new Promise((resolve, reject) => {
    let stdout = "";
    let stderr = "";
    let timer = null;
    if (timeoutMs > 0) {
      timer = setTimeout(() => {
        reject(new Error(`Command timed out after ${timeoutMs}ms`));
      }, timeoutMs);
    }
    conn.exec(command, (err, stream) => {
      if (err) {
        if (timer) clearTimeout(timer);
        reject(err);
        return;
      }
      stream
        .on("close", (code) => {
          if (timer) clearTimeout(timer);
          resolve({ exitCode: code ?? 0, stdout, stderr });
        })
        .on("data", (data) => {
          stdout += data.toString();
        });
      stream.stderr.on("data", (data) => {
        stderr += data.toString();
      });
    });
  });
}

/**
 * @param {object} [options]
 * @param {number} [options.timeoutMs] 0 = no timeout (default; PG install can run a long time).
 */
export function runScript(conn, scriptName, env = {}, options = {}) {
  const scriptPath = join(SCRIPTS_ROOT, scriptName);
  let body;
  try {
    body = readFileSync(scriptPath, "utf8");
  } catch {
    return Promise.reject(new Error(`Script not found: ${scriptName}`));
  }
  const exports = Object.entries(env)
    .map(([k, v]) => `export ${k}=${shellQuote(String(v))}`)
    .join("\n");
  const payload = `${exports}\n${body}`;
  const b64 = Buffer.from(payload, "utf8").toString("base64");
  const cmd = `echo ${shellQuote(b64)} | base64 -d | bash -s`;
  const timeoutMs = options.timeoutMs ?? 0;
  return execCommand(conn, cmd, timeoutMs);
}

function shellQuote(value) {
  return `'${value.replace(/'/g, `'\"'\"'`)}'`;
}

export function extractConnection(args) {
  return {
    host: required(args, "host"),
    sshPort: Number(args.sshPort ?? 22),
    sshUser: required(args, "sshUser"),
    authType: required(args, "authType"),
    password: args.password,
    privateKeyPem: args.privateKeyPem,
    passphrase: args.passphrase,
  };
}

function required(args, key) {
  const v = args[key];
  if (v == null || String(v).trim() === "") {
    throw new Error(`Missing required argument: ${key}`);
  }
  return String(v);
}

export async function withSsh(args, fn) {
  const params = extractConnection(args);
  const conn = await connectSsh(params);
  try {
    return await fn(conn, params);
  } finally {
    conn.end();
  }
}
