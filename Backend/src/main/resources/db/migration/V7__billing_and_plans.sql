-- V7: billing and plans — user plan columns, subscriptions, payments,
-- stripe_events (idempotency), usage_events and daily_quota_counters.

-- Extend users with plan / billing columns.
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan VARCHAR(16) NOT NULL DEFAULT 'FREE';
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_status VARCHAR(32);
ALTER TABLE users ADD COLUMN IF NOT EXISTS plan_valid_until TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(64);
ALTER TABLE users ADD COLUMN IF NOT EXISTS billing_mode_preference VARCHAR(16) NOT NULL DEFAULT 'AUTO';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_users_stripe_customer_id'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT uq_users_stripe_customer_id UNIQUE (stripe_customer_id);
    END IF;
END$$;

-- Stripe subscriptions.
CREATE TABLE IF NOT EXISTS subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_subscription_id VARCHAR(255) NOT NULL UNIQUE,
    plan VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_status
    ON subscriptions(user_id, status);

-- Stripe payments (one row per PaymentIntent).
CREATE TABLE IF NOT EXISTS payments (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_payment_intent_id VARCHAR(255) NOT NULL UNIQUE,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_payments_user ON payments(user_id);

-- Stripe webhook events — used to guarantee idempotent processing.
CREATE TABLE IF NOT EXISTS stripe_events (
    id BIGSERIAL PRIMARY KEY,
    stripe_event_id VARCHAR(255) NOT NULL UNIQUE,
    type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMPTZ
);

-- Per-call usage/cost accounting.
CREATE TABLE IF NOT EXISTS usage_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_id BIGINT REFERENCES messages(id) ON DELETE SET NULL,
    provider VARCHAR(32) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    cost_micro_cents BIGINT,
    billing_mode VARCHAR(16),
    tier_at_call VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_usage_events_user_created
    ON usage_events(user_id, created_at);

-- Daily quota counters for managed-mode calls.
CREATE TABLE IF NOT EXISTS daily_quota_counters (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day DATE NOT NULL,
    managed_calls_count BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, day)
);
