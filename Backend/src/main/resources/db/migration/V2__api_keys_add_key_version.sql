ALTER TABLE api_keys
    ADD COLUMN IF NOT EXISTS key_version INTEGER;

UPDATE api_keys
SET key_version = 1
WHERE key_version IS NULL;

ALTER TABLE api_keys
    ALTER COLUMN key_version SET NOT NULL;
