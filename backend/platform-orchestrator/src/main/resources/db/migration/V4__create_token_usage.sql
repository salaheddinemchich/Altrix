CREATE TABLE IF NOT EXISTS token_usage (
    id            BIGSERIAL    PRIMARY KEY,
    provider_id   VARCHAR(50)  NOT NULL,
    tier          VARCHAR(20)  NOT NULL,
    input_tokens  INT          NOT NULL DEFAULT 0,
    output_tokens INT          NOT NULL DEFAULT 0,
    total_tokens  INT          NOT NULL DEFAULT 0,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_token_usage_provider
    ON token_usage (provider_id, recorded_at DESC);

CREATE INDEX IF NOT EXISTS idx_token_usage_tier
    ON token_usage (tier, recorded_at DESC);
