-- Remove Stripe billing, paid-plan state, and managed-plan quota artifacts.

DROP TABLE IF EXISTS stripe_events;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS subscriptions;
DROP TABLE IF EXISTS daily_quota_counters;
DROP TABLE IF EXISTS audit_log;

DROP INDEX IF EXISTS idx_users_plan_status_valid_until;
DROP INDEX IF EXISTS idx_users_plan_valid_until;

ALTER TABLE users DROP CONSTRAINT IF EXISTS uq_users_stripe_customer_id;
ALTER TABLE users DROP COLUMN IF EXISTS plan;
ALTER TABLE users DROP COLUMN IF EXISTS plan_status;
ALTER TABLE users DROP COLUMN IF EXISTS plan_valid_until;
ALTER TABLE users DROP COLUMN IF EXISTS stripe_customer_id;
ALTER TABLE users DROP COLUMN IF EXISTS billing_mode_preference;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'usage_events'
          AND column_name = 'billing_mode'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'usage_events'
          AND column_name = 'credential_source'
    ) THEN
        ALTER TABLE usage_events RENAME COLUMN billing_mode TO credential_source;
    END IF;
END $$;

ALTER TABLE usage_events DROP COLUMN IF EXISTS tier_at_call;
