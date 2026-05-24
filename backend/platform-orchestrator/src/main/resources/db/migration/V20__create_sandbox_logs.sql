-- #105 — persist sandbox runner output per (session, runner) for post-run review.
--
-- Stores the FULL stdout+stderr blob each Docker runner captured.  One row
-- per (session, runner) — a re-run for the same session overwrites the
-- previous attempt's log (UPSERT on PK) so reviewers always see the
-- latest output without history bloat.
--
-- Granular per-line persistence isn't done here on purpose: the JobDetail
-- log-viewer renders the whole blob in a virtual-scroll list anyway, and
-- one row per line would multiply IO by 1000× without changing what the
-- user sees.  Add a `lines` table later if structured queries become
-- valuable (e.g. "show me every ERROR line across all sessions").

CREATE TABLE sandbox_logs (
    session_id   UUID         NOT NULL REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    runner_id    VARCHAR(64)  NOT NULL,
    content      TEXT         NOT NULL,
    exit_code    INTEGER,
    generated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, runner_id)
);

CREATE INDEX idx_sandbox_logs_session_id ON sandbox_logs (session_id);
