-- V6: chat_tools table for per-chat tool configuration
CREATE TABLE IF NOT EXISTS chat_tools (
    id BIGSERIAL PRIMARY KEY,
    chat_id UUID NOT NULL REFERENCES chats(chat_id) ON DELETE CASCADE,
    tool_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_chat_tools_chat_tool UNIQUE (chat_id, tool_name)
);

CREATE INDEX IF NOT EXISTS idx_chat_tools_chat ON chat_tools(chat_id);
