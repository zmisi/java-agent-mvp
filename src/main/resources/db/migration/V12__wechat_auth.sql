CREATE TABLE agent_ui.wechat_user (
    id BIGSERIAL PRIMARY KEY,
    openid VARCHAR(128) NOT NULL UNIQUE,
    unionid VARCHAR(128),
    nickname VARCHAR(128),
    avatar_url TEXT,
    province VARCHAR(64),
    city VARCHAR(64),
    gender INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE agent_ui.wechat_login_audit (
    id BIGSERIAL PRIMARY KEY,
    openid VARCHAR(128),
    login_status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(255),
    ip VARCHAR(64),
    user_agent VARCHAR(512),
    request_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wechat_login_audit_openid_created
    ON agent_ui.wechat_login_audit (openid, created_at DESC);

CREATE INDEX idx_wechat_login_audit_request_id
    ON agent_ui.wechat_login_audit (request_id);
