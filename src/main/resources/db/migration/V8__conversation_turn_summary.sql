CREATE TABLE IF NOT EXISTS agent_ui.conversation_turn_summary (
    conversation_id VARCHAR(80) NOT NULL REFERENCES agent_ui.conversation (id) ON DELETE CASCADE,
    turn_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    goal TEXT NOT NULL,
    finding TEXT NOT NULL,
    summary_row TEXT NOT NULL,
    PRIMARY KEY (conversation_id, turn_id)
);

CREATE INDEX IF NOT EXISTS idx_turn_summary_conv_turn_desc
    ON agent_ui.conversation_turn_summary (conversation_id, turn_id DESC);
