# java-agent-mcp

stdio MCP server for **DB Provisioning** in [java-agent-mvp](../README.md).

- SSH runs inside this process (not in Spring Boot).
- Exposes `provision_*` tools that execute idempotent shell scripts under `scripts/provisioning/`.

## Run

```bash
npm install
node bin/java-agent-mcp.js
```

java-agent-mvp starts this via `app.db-agent.provisioning-mcp-command` + `provisioning-mcp-args`.
