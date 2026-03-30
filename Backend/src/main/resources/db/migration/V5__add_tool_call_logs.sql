CREATE TABLE IF NOT EXISTS tool_call_logs (
    id BIGSERIAL PRIMARY KEY,
    message_id BIGINT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    tool_name VARCHAR(255) NOT NULL,
    input_payload TEXT NOT NULL,
    output_payload TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL,
    context_excerpt TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tool_call_logs_message_started
    ON tool_call_logs(message_id, started_at);
