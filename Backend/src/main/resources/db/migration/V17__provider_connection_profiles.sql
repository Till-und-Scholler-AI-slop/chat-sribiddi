ALTER TABLE provider_connections
    ADD COLUMN IF NOT EXISTS connection_profile_id VARCHAR(80) NOT NULL DEFAULT 'api-key';

UPDATE provider_connections
SET connection_profile_id = CASE auth_method
    WHEN 'OAUTH_PKCE' THEN 'openrouter-oauth'
    WHEN 'OAUTH_AUTH_CODE' THEN
        CASE provider_id
            WHEN 'google' THEN 'google-oauth'
            ELSE 'oauth'
        END
    WHEN 'DEVICE_CODE' THEN 'github-oauth'
    WHEN 'LOCAL_ENDPOINT' THEN 'local'
    WHEN 'OPENAI_COMPATIBLE' THEN 'api'
    ELSE 'api-key'
END
WHERE connection_profile_id = 'api-key'
  AND auth_method <> 'API_KEY';

ALTER TABLE provider_connections
    DROP CONSTRAINT IF EXISTS uq_provider_connections_user_provider_auth;

ALTER TABLE provider_connections
    ADD CONSTRAINT uq_provider_connections_user_provider_profile
        UNIQUE (user_id, provider_id, connection_profile_id);

CREATE INDEX IF NOT EXISTS idx_provider_connections_user_provider_profile
    ON provider_connections(user_id, provider_id, connection_profile_id);
