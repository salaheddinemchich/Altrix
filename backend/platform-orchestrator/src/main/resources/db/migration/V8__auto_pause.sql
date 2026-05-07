-- Auto-pause circuit-breaker: tracks how many consecutive agent failures have
-- occurred on a session. When the count reaches the configured threshold, the
-- session is paused instead of permanently failed, allowing manual intervention
-- and a subsequent resume attempt (#71).
ALTER TABLE workflow_sessions
    ADD COLUMN IF NOT EXISTS consecutive_agent_errors INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN workflow_sessions.consecutive_agent_errors
    IS 'Consecutive agent failure count — reset to 0 on success; triggers auto-pause when ≥ threshold';
