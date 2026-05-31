CREATE INDEX IF NOT EXISTS idx_rag_vector_store_content_fts
    ON agent_ui.rag_vector_store
    USING GIN (to_tsvector('simple', COALESCE(content, '')));
