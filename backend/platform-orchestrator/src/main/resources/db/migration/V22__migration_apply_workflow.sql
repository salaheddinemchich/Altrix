-- Migration Approval & Branch Strategy Workflow (#PR-feature)
--
-- Adds the per-session "apply to repository" state on workflow_sessions,
-- plus a dedicated append-only audit table that records every transition
-- in that workflow (strategy chosen, confirmation, branch creation,
-- PR opening, merge, cancellation, failure).  No secrets ever land in
-- the audit payload — only the parameters needed to reconstruct what
-- happened.
--
-- Columns are NULL-able so existing sessions remain valid; the apply
-- workflow only attaches once the user picks a strategy on a session
-- that has reached DONE.

ALTER TABLE workflow_sessions
    ADD COLUMN IF NOT EXISTS apply_strategy            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS apply_branch_name         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS apply_base_branch         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS apply_commit_message      TEXT,
    ADD COLUMN IF NOT EXISTS apply_pr_title            TEXT,
    ADD COLUMN IF NOT EXISTS apply_pr_body             TEXT,
    -- Server-issued single-use token the user must echo back on /apply.
    -- Cleared after successful apply or cancel.
    ADD COLUMN IF NOT EXISTS apply_confirmation_token  UUID,
    ADD COLUMN IF NOT EXISTS apply_confirmation_expires TIMESTAMPTZ,
    -- STRATEGY_SET | APPLIED | CANCELLED — drives the post-DONE UI state.
    ADD COLUMN IF NOT EXISTS apply_status              VARCHAR(20),
    ADD COLUMN IF NOT EXISTS apply_outcome             VARCHAR(20),
    ADD COLUMN IF NOT EXISTS apply_result_url          VARCHAR(500),
    ADD COLUMN IF NOT EXISTS apply_result_sha          VARCHAR(64),
    ADD COLUMN IF NOT EXISTS apply_actor_user_id       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS apply_completed_at        TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS migration_apply_audit_log (
    id            UUID         PRIMARY KEY,
    session_id    UUID         NOT NULL REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    actor_user_id VARCHAR(64)  NOT NULL,
    -- STRATEGY_CHOSEN | APPLY_CONFIRMED | BRANCH_CREATED | PR_CREATED
    -- | MERGED | CANCELLED | FAILED
    event_type    VARCHAR(40)  NOT NULL,
    -- Free-shape JSON; never include tokens / API keys / secrets here.
    payload       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_apply_audit_session   ON migration_apply_audit_log (session_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_apply_audit_actor     ON migration_apply_audit_log (actor_user_id, occurred_at);
