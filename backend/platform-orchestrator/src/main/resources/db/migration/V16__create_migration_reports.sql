-- #129 — persist the narrative MigrationReport that Agent 5 (Report
-- Generator) produces, so it can be served from GET /api/v1/sessions/
-- {id}/report long after the pipeline finishes.  One row per workflow
-- session; the FK with ON DELETE CASCADE keeps the table tidy when a
-- session is removed.

CREATE TABLE migration_reports (
    session_id   UUID         PRIMARY KEY REFERENCES workflow_sessions(id) ON DELETE CASCADE,
    project_id   VARCHAR(36)  NOT NULL,
    content      TEXT         NOT NULL,
    generated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Lookup of reports for a given project (across sessions / re-runs).
CREATE INDEX idx_migration_reports_project_id ON migration_reports (project_id);
