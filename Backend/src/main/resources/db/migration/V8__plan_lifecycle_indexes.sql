-- V8: Indexes supporting the PlanLifecycleJob (BILL-13) hourly reconciliation
-- queries. Without these, the scheduled scans degrade into full table scans
-- as subscriptions/users tables grow.

-- subscriptions(status, current_period_end) — supports
-- findByStatusAndCurrentPeriodEndBefore used to locate "still active but
-- period already ended" subscriptions.
CREATE INDEX IF NOT EXISTS idx_subscriptions_status_period_end
    ON subscriptions(status, current_period_end);

-- users(plan_status, plan_valid_until) — supports
-- findByPlanStatusAndPlanValidUntilBefore used to locate users whose
-- payment-failed grace period has elapsed.
CREATE INDEX IF NOT EXISTS idx_users_plan_status_valid_until
    ON users(plan_status, plan_valid_until);
