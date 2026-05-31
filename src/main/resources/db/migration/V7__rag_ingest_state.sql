CREATE TABLE IF NOT EXISTS agent_ui.rag_ingest_state (
    source VARCHAR(1024) PRIMARY KEY,
    content_hash CHAR(64) NOT NULL,
    chunk_ids TEXT NOT NULL,
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
