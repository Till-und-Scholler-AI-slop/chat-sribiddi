-- V10: Audit log for billing plan state changes.
-- Plan transitions originate from Stripe webhooks and scheduled lifecycle
-- reconciliation jobs. Keeping them in a dedicated append-only table gives
-- support/compliance a durable timeline without overloading operational rows.

CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    event_type VARCHAR(64) NOT NULL,
    source VARCHAR(64) NOT NULL,
    previous_plan VARCHAR(16),
    new_plan VARCHAR(16),
    previous_status VARCHAR(64),
    new_status VARCHAR(64),
    reason VARCHAR(128),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_log_user_created
    ON audit_log(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_event_created
    ON audit_log(event_type, created_at DESC);
