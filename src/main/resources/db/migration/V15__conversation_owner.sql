ALTER TABLE agent_ui.conversation
    ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES agent_ui.wechat_user (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_conversation_user_updated
    ON agent_ui.conversation (user_id, updated_at DESC)
    WHERE archived_at IS NULL;
