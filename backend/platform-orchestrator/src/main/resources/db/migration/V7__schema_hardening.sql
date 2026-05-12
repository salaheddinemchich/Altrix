-- ── platform-orchestrator: schema hardening, index refinement, materialized view ─
--
-- Addresses the following findings from the schema audit:
--
--   [A] provider_configs.enabled is NULLable — semantically ambiguous (NULL ≠ FALSE).
--   [B] workflow_sessions lacks a partial index for the common "active sessions" query,
--       a composite index for status+recency, and a cheap BRIN on created_at.
--   [C] ai_call_ledger has no per-job composite index (needed for cost drill-down).
--   [D] No precomputed aggregation for the billing dashboard — every request currently
--       runs a GROUP BY over a potentially large ledger table.
--   [E] Enum columns lack CHECK constraints for DB-level integrity.
--
-- Trade-off: partitioning ai_call_ledger by month (the correct long-term strategy)
--   requires a CREATE TABLE … PARTITION BY + data migration during a maintenance
--   window. This migration focuses on zero-downtime wins only. The partition plan is
--   documented below as a comment for the next maintenance sprint.

-- ── [A] Fix provider_configs.enabled nullability ─────────────────────────────
UPDATE provider_configs SET enabled = FALSE WHERE enabled IS NULL;
ALTER TABLE provider_configs
    ALTER COLUMN enabled SET NOT NULL,
    ALTER COLUMN enabled SET DEFAULT TRUE;

-- ── [B] workflow_sessions indexes ────────────────────────────────────────────

-- Partial index: active sessions — only non-terminal states.
-- The vast majority of queries hit active sessions; keeping this index small
-- ensures it stays in shared_buffers far longer than a full-table index.
CREATE INDEX IF NOT EXISTS idx_ws_active
    ON workflow_sessions (project_id, updated_at DESC)
    WHERE status NOT IN ('DONE', 'FAILED');

-- Composite: status + recency — dashboard "show recent MIGRATING sessions".
-- status has low cardinality so it must come FIRST to reduce the scanned range.
CREATE INDEX IF NOT EXISTS idx_ws_status_updated
    ON workflow_sessions (status, updated_at DESC);

-- BRIN: created_at — near-zero storage, useful for time-range scans on this
-- append-mostly table (new sessions always append at the end of the heap).
CREATE INDEX IF NOT EXISTS idx_ws_created_brin
    ON workflow_sessions USING brin (created_at)
    WITH (pages_per_range = 32);

-- Enum guard
ALTER TABLE workflow_sessions
    ADD CONSTRAINT chk_ws_status
        CHECK (status IN ('PENDING','CONTEXT_ANALYSED','PLAN_READY',
                          'AWAITING_APPROVAL','MIGRATING','VALIDATING',
                          'DONE','FAILED','PAUSED'));

-- Statistics
ALTER TABLE workflow_sessions ALTER COLUMN status      SET STATISTICS 200;
ALTER TABLE workflow_sessions ALTER COLUMN paused_from SET STATISTICS 100;

-- ── [C] ai_call_ledger: per-job cost drill-down index ───────────────────────
-- Covers: SELECT … WHERE job_id = ? ORDER BY created_at DESC
-- Already have idx_ledger_job_id (single-column); this composite adds agent_name
-- for the "cost per agent per job" breakdown used by BillingController.
CREATE INDEX IF NOT EXISTS idx_ledger_job_agent
    ON ai_call_ledger (job_id, agent_name, created_at DESC)
    WHERE job_id IS NOT NULL;

-- Statistics
ALTER TABLE ai_call_ledger ALTER COLUMN provider_name SET STATISTICS 200;
ALTER TABLE ai_call_ledger ALTER COLUMN tier          SET STATISTICS 200;
ALTER TABLE ai_call_ledger ALTER COLUMN agent_name    SET STATISTICS 200;

-- ── [D] Materialized view: daily billing aggregates ──────────────────────────
-- Replaces the per-request GROUP BY in BillingController for date-range queries
-- wider than a few days. Refreshed concurrently once per hour (no lock on reads).
--
-- Usage: SELECT * FROM mv_daily_ai_cost WHERE day >= NOW() - INTERVAL '30 days'
-- Refresh: REFRESH MATERIALIZED VIEW CONCURRENTLY mv_daily_ai_cost;
--          (schedule with pg_cron or a Spring @Scheduled task)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_daily_ai_cost AS
    SELECT
        date_trunc('day', created_at AT TIME ZONE 'UTC') AS day,
        provider_name,
        tier,
        agent_name,
        SUM(input_tokens)                                AS total_input_tokens,
        SUM(output_tokens)                               AS total_output_tokens,
        SUM(cost_usd)                                    AS total_cost_usd,
        COUNT(*)                                         AS call_count,
        SUM(CASE WHEN cache_hit THEN 1 ELSE 0 END)       AS cache_hit_count
    FROM ai_call_ledger
    GROUP BY 1, 2, 3, 4
    ORDER BY 1 DESC, 7 DESC
WITH DATA;

-- Unique index allows CONCURRENT refresh (zero read-lock)
CREATE UNIQUE INDEX IF NOT EXISTS uidx_daily_cost_key
    ON mv_daily_ai_cost (day, provider_name, tier, agent_name);

CREATE INDEX IF NOT EXISTS idx_daily_cost_day
    ON mv_daily_ai_cost (day DESC);

-- ── ANALYZE ───────────────────────────────────────────────────────────────────
ANALYZE workflow_sessions;
ANALYZE ai_call_ledger;
ANALYZE provider_configs;

-- ─────────────────────────────────────────────────────────────────────────────
-- FUTURE: ai_call_ledger monthly partitioning (maintenance window required)
-- ─────────────────────────────────────────────────────────────────────────────
-- When the ledger exceeds ~5 M rows, convert to a partitioned table:
--
--   BEGIN;
--   CREATE TABLE ai_call_ledger_new (LIKE ai_call_ledger INCLUDING ALL)
--       PARTITION BY RANGE (created_at);
--
--   CREATE TABLE ai_call_ledger_2025_01
--       PARTITION OF ai_call_ledger_new
--       FOR VALUES FROM ('2025-01-01') TO ('2025-02-01');
--   -- (repeat for each month)
--
--   INSERT INTO ai_call_ledger_new SELECT * FROM ai_call_ledger;
--   ALTER TABLE ai_call_ledger RENAME TO ai_call_ledger_old;
--   ALTER TABLE ai_call_ledger_new RENAME TO ai_call_ledger;
--   COMMIT;
--
-- Benefit: DROP TABLE ai_call_ledger_old_<month> is instantaneous vs slow DELETE.
-- Benefit: Query on a 30-day window only scans 1–2 partitions, not the full table.
-- ─────────────────────────────────────────────────────────────────────────────

COMMENT ON MATERIALIZED VIEW mv_daily_ai_cost
    IS 'Precomputed daily AI cost aggregates — refresh hourly via pg_cron or Spring @Scheduled';
COMMENT ON INDEX idx_ws_active
    IS 'Covers active-session lookups — excludes DONE/FAILED rows (the majority at scale)';
COMMENT ON INDEX idx_ws_status_updated
    IS 'Dashboard: status-filtered recency sort';
COMMENT ON INDEX idx_ws_created_brin
    IS 'Cheap BRIN for time-range scans on append-mostly sessions table';
