CREATE SCHEMA IF NOT EXISTS agent_ui;

CREATE TABLE agent_ui.conversation (
    id VARCHAR(80) PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE agent_ui.chat_memory_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(80) NOT NULL REFERENCES agent_ui.conversation (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload JSONB NOT NULL
);

CREATE INDEX idx_chat_memory_message_conv_id ON agent_ui.chat_memory_message (conversation_id, id);
