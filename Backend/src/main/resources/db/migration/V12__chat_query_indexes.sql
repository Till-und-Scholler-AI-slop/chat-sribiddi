-- V12: Query indexes for chat-heavy paths.
--
-- Chat lists are fetched per user ordered by newest chat first. The original user-only
-- index still required an extra sort once a user accumulated many chats.
CREATE INDEX IF NOT EXISTS idx_chats_user_created
    ON chats(user_id, created_at DESC);

-- Editing a message deletes all later messages in the same chat using
-- (chat_id, id > edited_id). The existing created_at index supports history listing, while
-- this index supports edit truncation and chat-scoped id lookups.
CREATE INDEX IF NOT EXISTS idx_messages_chat_id
    ON messages(chat_id, id);
