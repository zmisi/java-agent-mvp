DO $$
BEGIN
    IF to_regclass('agent_ui.rag_vector_store') IS NOT NULL THEN
        CREATE INDEX IF NOT EXISTS idx_rag_vector_store_content_fts
            ON agent_ui.rag_vector_store
            USING GIN (to_tsvector('simple', COALESCE(content, '')));
    END IF;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Skip creating lexical index for rag_vector_store: %', SQLERRM;
END
$$;
