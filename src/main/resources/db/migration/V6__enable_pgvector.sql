DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
EXCEPTION
    WHEN OTHERS THEN
        RAISE NOTICE 'Skip enabling pgvector extension: %', SQLERRM;
END
$$;
