-- Align db_agent.provisioning_* with current application (replaces early V3 draft columns).

-- ---- provisioning_request ----
ALTER TABLE db_agent.provisioning_request
    ADD COLUMN IF NOT EXISTS host VARCHAR(256),
    ADD COLUMN IF NOT EXISTS ssh_port INT,
    ADD COLUMN IF NOT EXISTS ssh_user VARCHAR(128),
    ADD COLUMN IF NOT EXISTS auth_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS schema_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS memory_mb INT,
    ADD COLUMN IF NOT EXISTS disk_gb INT,
    ADD COLUMN IF NOT EXISTS data_directory VARCHAR(512),
    ADD COLUMN IF NOT EXISTS extensions JSONB DEFAULT '[]',
    ADD COLUMN IF NOT EXISTS db_owner_user VARCHAR(128),
    ADD COLUMN IF NOT EXISTS error_summary TEXT,
    ADD COLUMN IF NOT EXISTS connection_hint VARCHAR(1024);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'db_agent' AND table_name = 'provisioning_request' AND column_name = 'server_target'
    ) THEN
        UPDATE db_agent.provisioning_request
        SET host = server_target
        WHERE host IS NULL;
        ALTER TABLE db_agent.provisioning_request DROP COLUMN server_target;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'db_agent' AND table_name = 'provisioning_request' AND column_name = 'owner_username'
    ) THEN
        UPDATE db_agent.provisioning_request
        SET db_owner_user = owner_username
        WHERE db_owner_user IS NULL;
        ALTER TABLE db_agent.provisioning_request DROP COLUMN owner_username;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'db_agent' AND table_name = 'provisioning_request' AND column_name = 'init_sql'
    ) THEN
        ALTER TABLE db_agent.provisioning_request DROP COLUMN init_sql;
    END IF;
END $$;

UPDATE db_agent.provisioning_request
SET ssh_port = COALESCE(ssh_port, 22),
    ssh_user = COALESCE(ssh_user, 'ubuntu'),
    auth_type = COALESCE(auth_type, 'PASSWORD'),
    schema_name = COALESCE(schema_name, database_name),
    memory_mb = COALESCE(memory_mb, 2048),
    disk_gb = COALESCE(disk_gb, 20),
    extensions = COALESCE(extensions, '[]'::jsonb)
WHERE host IS NOT NULL;

ALTER TABLE db_agent.provisioning_request
    ALTER COLUMN ssh_port SET DEFAULT 22,
    ALTER COLUMN ssh_port SET NOT NULL,
    ALTER COLUMN ssh_user SET NOT NULL,
    ALTER COLUMN auth_type SET NOT NULL,
    ALTER COLUMN schema_name SET NOT NULL,
    ALTER COLUMN memory_mb SET NOT NULL,
    ALTER COLUMN disk_gb SET NOT NULL,
    ALTER COLUMN extensions SET NOT NULL,
    ALTER COLUMN extensions SET DEFAULT '[]';

-- ---- provisioning_step ----
ALTER TABLE db_agent.provisioning_step
    ADD COLUMN IF NOT EXISTS step_name VARCHAR(64),
    ADD COLUMN IF NOT EXISTS log_text TEXT;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'db_agent' AND table_name = 'provisioning_step' AND column_name = 'step_key'
    ) THEN
        UPDATE db_agent.provisioning_step
        SET step_name = step_key
        WHERE step_name IS NULL;
        ALTER TABLE db_agent.provisioning_step DROP COLUMN step_key;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'db_agent' AND table_name = 'provisioning_step' AND column_name = 'step_label'
    ) THEN
        ALTER TABLE db_agent.provisioning_step DROP COLUMN step_label;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'db_agent' AND table_name = 'provisioning_step' AND column_name = 'log'
    ) THEN
        UPDATE db_agent.provisioning_step
        SET log_text = log
        WHERE log_text IS NULL;
        ALTER TABLE db_agent.provisioning_step DROP COLUMN log;
    END IF;
END $$;

ALTER TABLE db_agent.provisioning_step
    DROP CONSTRAINT IF EXISTS provisioning_step_request_id_step_key_key;

ALTER TABLE db_agent.provisioning_step
    DROP CONSTRAINT IF EXISTS provisioning_step_request_id_step_name_key;

ALTER TABLE db_agent.provisioning_step
    ADD CONSTRAINT provisioning_step_request_id_step_name_key UNIQUE (request_id, step_name);

CREATE INDEX IF NOT EXISTS idx_provisioning_request_updated ON db_agent.provisioning_request (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_provisioning_step_request ON db_agent.provisioning_step (request_id, sort_order);
