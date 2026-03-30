CREATE TABLE usage_stats (
    id          BIGSERIAL PRIMARY KEY,
    user_id     VARCHAR(255) NOT NULL,
    chat_id     UUID         NOT NULL,
    model       VARCHAR(255) NOT NULL,
    provider    VARCHAR(50)  NOT NULL,
    input_tokens  BIGINT,
    output_tokens BIGINT,
    total_tokens  BIGINT,
    estimated_cost DOUBLE PRECISION,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_usage_stats_user_created ON usage_stats (user_id, created_at);
CREATE INDEX idx_usage_stats_user_provider ON usage_stats (user_id, provider);
