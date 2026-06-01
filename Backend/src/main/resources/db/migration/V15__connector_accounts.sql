CREATE TABLE IF NOT EXISTS connector_accounts (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    encrypted_refresh_token TEXT,
    granted_scopes TEXT NOT NULL DEFAULT '',
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    token_expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_connector_accounts_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_connector_accounts_user_provider
    ON connector_accounts(user_id, provider);
