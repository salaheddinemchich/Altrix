-- Per-call AI usage ledger for billing and cost anomaly detection (#53).
CREATE TABLE IF NOT EXISTS ai_call_ledger (
    id            BIGSERIAL     PRIMARY KEY,
    job_id        VARCHAR(36),
    agent_name    VARCHAR(100),
    provider_name VARCHAR(50)   NOT NULL,
    model_name    VARCHAR(100),
    tier          VARCHAR(20)   NOT NULL,
    input_tokens  BIGINT        NOT NULL DEFAULT 0,
    output_tokens BIGINT        NOT NULL DEFAULT 0,
    cost_usd      NUMERIC(12,8) NOT NULL DEFAULT 0,
    cache_hit     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_created_at
    ON ai_call_ledger (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ledger_provider_tier
    ON ai_call_ledger (provider_name, tier, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ledger_job_id
    ON ai_call_ledger (job_id)
    WHERE job_id IS NOT NULL;
