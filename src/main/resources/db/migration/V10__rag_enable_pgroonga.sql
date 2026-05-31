DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pgroonga;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Skip enabling pgroonga extension: %', SQLERRM;
END
$$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pgroonga') THEN
        CREATE INDEX IF NOT EXISTS idx_rag_vector_store_content_pgroonga
            ON agent_ui.rag_vector_store
            USING pgroonga (content);
    END IF;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Skip creating pgroonga index for rag_vector_store: %', SQLERRM;
END
$$;
