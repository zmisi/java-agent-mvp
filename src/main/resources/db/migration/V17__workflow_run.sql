CREATE TABLE agent_ui.workflow_run (
    id UUID PRIMARY KEY,
    workflow_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    user_id BIGINT REFERENCES agent_ui.wechat_user (id) ON DELETE SET NULL,
    conversation_id VARCHAR(80) REFERENCES agent_ui.conversation (id) ON DELETE SET NULL,
    input_message TEXT NOT NULL,
    result_json JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ
);

CREATE TABLE agent_ui.workflow_checkpoint (
    id BIGSERIAL PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_ui.workflow_run (id) ON DELETE CASCADE,
    node_name VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json JSONB,
    output_json JSONB,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_workflow_run_user_created
    ON agent_ui.workflow_run (user_id, created_at DESC);

CREATE INDEX idx_workflow_checkpoint_run_seq
    ON agent_ui.workflow_checkpoint (run_id, sequence_no);
