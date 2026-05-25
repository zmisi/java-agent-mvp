CREATE SCHEMA IF NOT EXISTS db_agent;

CREATE TABLE db_agent.release (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    design_doc_path VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE db_agent.release_script (
    id BIGSERIAL PRIMARY KEY,
    release_id VARCHAR(64) NOT NULL REFERENCES db_agent.release (id) ON DELETE CASCADE,
    file_name VARCHAR(256) NOT NULL,
    sql_content TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    review_comment TEXT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (release_id, file_name)
);

CREATE TABLE db_agent.deployment (
    id BIGSERIAL PRIMARY KEY,
    release_id VARCHAR(64) NOT NULL REFERENCES db_agent.release (id) ON DELETE CASCADE,
    environment VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    log TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_release_updated_at ON db_agent.release (updated_at DESC);
CREATE INDEX idx_release_script_release_id ON db_agent.release_script (release_id, sort_order);
CREATE INDEX idx_deployment_release_id ON db_agent.deployment (release_id, started_at DESC);
