-- #125 + #126 — capture WHO approved / rejected a session, and WHEN,
-- so the JobDetail UI can show an approval history instead of just the
-- terminal status.  decision_kind discriminates between APPROVED and
-- REJECTED so we don't have to infer it from status (a session moves
-- from AWAITING_APPROVAL → MIGRATING for approve, AWAITING_APPROVAL →
-- FAILED for reject, but it's cleaner to record the decision explicitly).
--
-- All three columns are nullable: pre-existing rows have no recorded
-- decision, and a session sitting at PENDING / CONTEXT_ANALYSED / etc.
-- has not yet been decided.

ALTER TABLE workflow_sessions
    ADD COLUMN decided_by    VARCHAR(255),
    ADD COLUMN decided_at    TIMESTAMPTZ,
    ADD COLUMN decision_kind VARCHAR(16);

-- Index supports the approval-history endpoint which orders by decided_at desc.
-- Partial index because most rows will have decided_at = NULL.
CREATE INDEX idx_workflow_sessions_decided_at
    ON workflow_sessions (decided_at DESC)
    WHERE decided_at IS NOT NULL;
