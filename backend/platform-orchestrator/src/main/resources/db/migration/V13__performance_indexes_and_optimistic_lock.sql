-- V13: Performance indexes, optimistic lock, and schema improvements
--
-- [A] Optimistic locking on workflow_sessions — prevents lost-update race conditions
--     when the pipeline worker and the REST API (approve/pause/resume) update the
--     same session concurrently. Hibernate increments `version` on every UPDATE;
--     a stale-read attempt throws OptimisticLockException → 409 Conflict at the API.
--
-- [B] token_usage indexes — the budget-enforcement query (sumTotalTokensSince) does
--     a full table scan. At 10 k rows/day this is fine; at 1 M rows it becomes the
--     hot-path bottleneck. The composite covering index lets Postgres satisfy the
--     query with an index-only scan.
--
-- [C] refresh_tokens: partial index for active-token revocation — revokeAllForUser
--     only needs to touch non-revoked rows; a partial index makes this O(active)
--     instead of O(all). After a breach, family revocation fires once and is fast.
--
-- [D] users: index on role — needed for ADMIN lookup and role-based pagination.
--
-- [E] ANALYZE freshly indexed tables.

-- ── [A] Optimistic lock ───────────────────────────────────────────────────────
ALTER TABLE workflow_sessions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN workflow_sessions.version
    IS 'JPA optimistic lock counter — incremented by Hibernate on every UPDATE';

-- ── [B] token_usage indexes ───────────────────────────────────────────────────

-- Covering index for budget enforcement: WHERE recorded_at >= :since → SUM(total_tokens)
-- Index-only scan possible because total_tokens is INCLUDEd (Postgres 11+).
CREATE INDEX IF NOT EXISTS idx_tu_recorded_covering
    ON token_usage (recorded_at DESC)
    INCLUDE (total_tokens);

-- Composite for the GROUP BY summary query (provider_id, tier).
-- Pre-sorts the groups so Postgres avoids a hash aggregate on large sets.
CREATE INDEX IF NOT EXISTS idx_tu_provider_tier_recorded
    ON token_usage (provider_id, tier, recorded_at DESC);

-- ── [C] refresh_tokens: partial index for active-only revocation ──────────────
-- revokeAllForUser: WHERE github_id = ? AND revoked = false
-- At steady state most rows are already revoked; this index stays tiny.
CREATE INDEX IF NOT EXISTS idx_rt_active_by_user
    ON refresh_tokens (github_id)
    WHERE revoked = false;

-- Hot-path: findByTokenHash — the existing idx_refresh_tokens_hash covers this,
-- but a partial variant covering only active tokens gives the planner a smaller,
-- more cache-resident structure for the 99% case (valid token lookup).
CREATE INDEX IF NOT EXISTS idx_rt_active_hash
    ON refresh_tokens (token_hash)
    WHERE revoked = false;

-- ── [D] users: role index ─────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_users_role
    ON users (role);

-- ── [E] ANALYZE ───────────────────────────────────────────────────────────────
ANALYZE workflow_sessions;
ANALYZE token_usage;
ANALYZE refresh_tokens;
ANALYZE users;

COMMENT ON INDEX idx_tu_recorded_covering
    IS 'Covering index for monthly budget enforcement — enables index-only scan';
COMMENT ON INDEX idx_tu_provider_tier_recorded
    IS 'Pre-sorted groups for provider/tier billing summary query';
COMMENT ON INDEX idx_rt_active_by_user
    IS 'Partial index: revokeAllForUser scans only non-revoked tokens';
COMMENT ON INDEX idx_rt_active_hash
    IS 'Partial index: fast lookup for the 99% case (active, non-expired token)';
