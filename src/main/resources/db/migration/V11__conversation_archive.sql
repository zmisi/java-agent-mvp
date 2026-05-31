ALTER TABLE agent_ui.conversation
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_conversation_active_updated_at
    ON agent_ui.conversation (updated_at DESC)
    WHERE archived_at IS NULL;
