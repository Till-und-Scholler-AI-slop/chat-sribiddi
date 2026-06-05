CREATE TABLE IF NOT EXISTS provider_connections (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    provider_id VARCHAR(80) NOT NULL,
    auth_method VARCHAR(64) NOT NULL,
    status VARCHAR(64) NOT NULL DEFAULT 'READY',
    display_name VARCHAR(255),
    provider_account_id VARCHAR(255),
    provider_account_label VARCHAR(255),
    scopes TEXT,
    encrypted_secret_json TEXT,
    token_expires_at TIMESTAMPTZ,
    key_version INTEGER NOT NULL DEFAULT 1,
    default_connection BOOLEAN NOT NULL DEFAULT FALSE,
    last_validated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_provider_connections_user_provider_auth
        UNIQUE (user_id, provider_id, auth_method)
);

CREATE INDEX IF NOT EXISTS idx_provider_connections_user_provider
    ON provider_connections(user_id, provider_id);

CREATE INDEX IF NOT EXISTS idx_provider_connections_user_status
    ON provider_connections(user_id, status);

INSERT INTO provider_connections (
    user_id,
    provider_id,
    auth_method,
    status,
    display_name,
    encrypted_secret_json,
    key_version,
    default_connection,
    last_validated_at,
    created_at,
    updated_at
)
SELECT
    user_id,
    CASE provider
        WHEN 'CLAUDE' THEN 'anthropic'
        WHEN 'BRAVE_SEARCH' THEN 'brave-search'
        ELSE LOWER(provider)
    END AS provider_id,
    'API_KEY' AS auth_method,
    'READY' AS status,
    CASE provider
        WHEN 'OPENAI' THEN 'OpenAI'
        WHEN 'GOOGLE' THEN 'Google Gemini'
        WHEN 'CLAUDE' THEN 'Anthropic Claude'
        WHEN 'OPENROUTER' THEN 'OpenRouter'
        WHEN 'BRAVE_SEARCH' THEN 'Brave Search'
        ELSE provider
    END AS display_name,
    encrypted_key AS encrypted_secret_json,
    key_version,
    TRUE AS default_connection,
    NOW() AS last_validated_at,
    created_at,
    updated_at
FROM api_keys
ON CONFLICT (user_id, provider_id, auth_method) DO NOTHING;
