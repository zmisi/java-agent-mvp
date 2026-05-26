ALTER TABLE db_agent.provisioning_request
    ADD COLUMN IF NOT EXISTS pg_major_version INT NOT NULL DEFAULT 18,
    ADD COLUMN IF NOT EXISTS os_family VARCHAR(32),
    ADD COLUMN IF NOT EXISTS os_version_label VARCHAR(256);
