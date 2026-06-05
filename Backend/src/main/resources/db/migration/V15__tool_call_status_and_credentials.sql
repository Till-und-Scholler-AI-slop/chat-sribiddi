ALTER TABLE tool_call_logs
    ADD COLUMN IF NOT EXISTS client_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN IF NOT EXISTS error_message TEXT,
    ADD COLUMN IF NOT EXISTS display_type VARCHAR(64);

UPDATE tool_call_logs
SET status = 'SUCCESS'
WHERE status IS NULL;

CREATE TABLE IF NOT EXISTS tool_credentials (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    key_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_tool_credentials_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_tool_credentials_user_provider
    ON tool_credentials(user_id, provider);
