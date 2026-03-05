ALTER TABLE messages ADD COLUMN prompt_tokens BIGINT;
ALTER TABLE messages ADD COLUMN generation_tokens BIGINT;
ALTER TABLE messages ADD COLUMN total_tokens BIGINT;
