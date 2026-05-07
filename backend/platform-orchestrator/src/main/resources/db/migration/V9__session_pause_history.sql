-- Persists a timestamped log of every pause and resume for each session (#72).
-- Useful for audit trails, SLA measurement, and the session-detail API.
CREATE TABLE IF NOT EXISTS session_pause_history (
    id          BIGSERIAL    PRIMARY KEY,
    session_id  UUID         NOT NULL REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    paused_from VARCHAR(30)  NOT NULL,
    paused_at   TIMESTAMPTZ  NOT NULL,
    resumed_at  TIMESTAMPTZ,
    CONSTRAINT chk_ph_paused_from
        CHECK (paused_from IN ('PENDING','CONTEXT_ANALYSED','PLAN_READY',
                               'AWAITING_APPROVAL','MIGRATING','VALIDATING',
                               'DONE','FAILED','PAUSED'))
);

CREATE INDEX IF NOT EXISTS idx_ph_session_id
    ON session_pause_history (session_id, paused_at DESC);

COMMENT ON TABLE session_pause_history
    IS 'Append-only pause/resume audit log per WorkflowSession (#72)';
