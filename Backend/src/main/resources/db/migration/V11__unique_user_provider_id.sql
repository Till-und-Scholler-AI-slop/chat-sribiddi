ALTER TABLE users
    ALTER COLUMN provider_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_provider_id
    ON users(provider_id);
