CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE,
    provider_id VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS chats (
    chat_id UUID PRIMARY KEY,
    title VARCHAR(255),
    created_at TIMESTAMPTZ,
    user_id VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGSERIAL PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(chat_id) ON DELETE CASCADE,
    message TEXT NOT NULL,
    context TEXT,
    role VARCHAR(32),
    created_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS api_keys (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    encrypted_key TEXT NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_api_keys_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX IF NOT EXISTS idx_chats_user ON chats(user_id);
CREATE INDEX IF NOT EXISTS idx_messages_chat_created ON messages(chat_id, created_at DESC);
