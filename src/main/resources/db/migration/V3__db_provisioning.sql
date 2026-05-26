-- Do not edit after apply in shared DBs; add V4__*.sql for changes. Dev checksum fix: mvn flyway:repair
CREATE TABLE IF NOT EXISTS db_agent.provisioning_request (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    host VARCHAR(256) NOT NULL,
    ssh_port INT NOT NULL DEFAULT 22,
    ssh_user VARCHAR(128) NOT NULL,
    auth_type VARCHAR(32) NOT NULL,
    database_name VARCHAR(128) NOT NULL,
    schema_name VARCHAR(128) NOT NULL,
    memory_mb INT NOT NULL,
    disk_gb INT NOT NULL,
    data_directory VARCHAR(512),
    extensions JSONB NOT NULL DEFAULT '[]',
    db_owner_user VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    error_summary TEXT,
    connection_hint VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS db_agent.provisioning_step (
    id BIGSERIAL PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL REFERENCES db_agent.provisioning_request (id) ON DELETE CASCADE,
    step_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    log_text TEXT,
    sort_order INT NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    UNIQUE (request_id, step_name)
);

CREATE INDEX IF NOT EXISTS idx_provisioning_request_updated ON db_agent.provisioning_request (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_provisioning_step_request ON db_agent.provisioning_step (request_id, sort_order);
