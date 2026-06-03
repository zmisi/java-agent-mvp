ALTER TABLE agent_ui.wechat_user
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'guest',
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active';

CREATE TABLE agent_ui.auth_session (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES agent_ui.wechat_user (id) ON DELETE CASCADE,
    jwt_jti VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);

CREATE INDEX idx_auth_session_user_id ON agent_ui.auth_session (user_id);
CREATE INDEX idx_auth_session_jti ON agent_ui.auth_session (jwt_jti);

CREATE TABLE agent_ui.guest_usage (
    user_id BIGINT PRIMARY KEY REFERENCES agent_ui.wechat_user (id) ON DELETE CASCADE,
    used_count INTEGER NOT NULL DEFAULT 0,
    quota_limit INTEGER NOT NULL DEFAULT 10,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_ui.guest_usage_event (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES agent_ui.wechat_user (id) ON DELETE CASCADE,
    action VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_guest_usage_event_user_id ON agent_ui.guest_usage_event (user_id, created_at DESC);
