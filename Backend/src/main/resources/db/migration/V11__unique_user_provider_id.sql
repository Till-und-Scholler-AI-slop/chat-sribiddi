-- Historical rows predate the NOT NULL / UNIQUE constraint. Normalize them first so deploying
-- the constraint cannot be blocked by old nullable rows or by duplicates created during concurrent
-- first-login races. Duplicate rows are preserved under synthetic provider ids; the canonical row
-- keeps the real OAuth subject.
UPDATE users
SET provider_id = '__legacy_null_provider_id__' || id
WHERE provider_id IS NULL;

WITH ranked AS (
    SELECT
        id,
        row_number() OVER (
            PARTITION BY provider_id
            ORDER BY
                CASE WHEN plan <> 'FREE' THEN 0 ELSE 1 END,
                CASE WHEN stripe_customer_id IS NOT NULL THEN 0 ELSE 1 END,
                plan_valid_until DESC NULLS LAST,
                id ASC
        ) AS duplicate_rank
    FROM users
)
UPDATE users u
SET provider_id = u.provider_id || '__duplicate__' || u.id
FROM ranked r
WHERE u.id = r.id
  AND r.duplicate_rank > 1;

ALTER TABLE users
    ALTER COLUMN provider_id SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_provider_id
    ON users(provider_id);
