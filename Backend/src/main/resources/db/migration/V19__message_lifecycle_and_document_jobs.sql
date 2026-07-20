-- Persisted message lifecycle. Existing rows predate lifecycle tracking and are
-- complete by definition.
ALTER TABLE messages
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'COMPLETE',
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);

UPDATE messages SET status = 'COMPLETE' WHERE status IS NULL;

ALTER TABLE messages
    DROP CONSTRAINT IF EXISTS chk_messages_status;
ALTER TABLE messages
    ADD CONSTRAINT chk_messages_status
        CHECK (status IN ('STREAMING', 'COMPLETE', 'INCOMPLETE', 'FAILED'));

CREATE INDEX IF NOT EXISTS idx_messages_streaming
    ON messages(status)
    WHERE status = 'STREAMING';

-- chat_documents doubles as the durable extraction queue. The source payload is
-- kept only while a job is PROCESSING and is cleared on READY/FAILED.
ALTER TABLE chat_documents
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS payload BYTEA,
    ADD COLUMN IF NOT EXISTS attempts SMALLINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE chat_documents
    DROP CONSTRAINT IF EXISTS chk_chat_documents_status;
ALTER TABLE chat_documents
    ADD CONSTRAINT chk_chat_documents_status
        CHECK (status IN ('PROCESSING', 'READY', 'FAILED'));

ALTER TABLE chat_documents
    DROP CONSTRAINT IF EXISTS chk_chat_documents_attempts;
ALTER TABLE chat_documents
    ADD CONSTRAINT chk_chat_documents_attempts
        CHECK (attempts BETWEEN 0 AND 3);

CREATE INDEX IF NOT EXISTS idx_chat_documents_processing
    ON chat_documents(next_attempt_at, claimed_at, id)
    WHERE status = 'PROCESSING' AND attempts < 3;
