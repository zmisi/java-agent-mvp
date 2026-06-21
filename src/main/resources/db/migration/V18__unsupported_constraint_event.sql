CREATE TABLE agent_ui.unsupported_constraint_event (
    id UUID PRIMARY KEY,
    conversation_id VARCHAR(80) REFERENCES agent_ui.conversation (id) ON DELETE SET NULL,
    user_id BIGINT REFERENCES agent_ui.wechat_user (id) ON DELETE SET NULL,
    channel VARCHAR(32) NOT NULL DEFAULT 'chat',
    task VARCHAR(32),
    raw_message TEXT NOT NULL,
    raw_phrase TEXT NOT NULL,
    constraint_type VARCHAR(64) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    compiler_source VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_unsupported_constraint_type_created
    ON agent_ui.unsupported_constraint_event (constraint_type, created_at DESC);

CREATE INDEX idx_unsupported_constraint_conversation
    ON agent_ui.unsupported_constraint_event (conversation_id, created_at DESC);
