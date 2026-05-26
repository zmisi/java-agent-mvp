---
name: db-provisioning
description: >-
  Implement or debug java-agent-mvp DB Provisioning: form-driven async jobs
  that install PostgreSQL 18 on Linux via java-agent-mcp (MCP-only, no Spring SSH),
  create database/schema, and install extensions idempotently.
---

# DB Provisioning (java-agent-mvp)

## Architecture

- **UI**: `index.html` + `provisioning.js` — form modal, workspace pipeline, poll while `RUNNING`
- **API**: `POST /api/db-provisioning`, `GET /api/db-provisioning/{id}`
- **Persistence**: Flyway `V3__db_provisioning.sql` — `db_agent.provisioning_request`, `provisioning_step`
- **Execution (MCP-only)**:
  - `ProvisioningMcpClient` → `LazyProvisioningMcpClientFactory` → **java-agent-mcp** (stdio, Node)
  - SSH and scripts run inside MCP; Spring only calls `provision_*` tools
- **Chat MCP (separate)**: `mcp-servers-config.json` — Postgres readonly; not used for provisioning

## java-agent-mcp tools

`provision_ping`, `provision_detect_os`, `provision_check_pg18`, `provision_install_pg18`,
`provision_tune_memory`, `provision_check_disk`, `provision_create_database`,
`provision_install_extension`, `provision_verify`

Scripts: `java-agent-mcp/scripts/provisioning/*.sh`

## Config

```yaml
app.db-agent.provisioning-mcp-command: node
app.db-agent.provisioning-mcp-args: ["./java-agent-mcp/bin/java-agent-mcp.js"]
```

Env: `JAVA_AGENT_MCP_COMMAND`, `JAVA_AGENT_MCP_SCRIPT`

## Idempotency

- PG 18 already installed → `CHECK_PG_VERSION` / `INSTALL_PG18` → `SKIPPED`
- Extension exists → `CREATE EXTENSION IF NOT EXISTS` in script

## Security

- Never store SSH/DB passwords in DB or logs; use `ProvisioningLogRedactor`
- Do not add JSch/sshd to Spring Boot

## Setup

```bash
cd java-agent-mcp && npm install
chmod +x bin/java-agent-mcp.js
```
