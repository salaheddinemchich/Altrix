-- #162 — append-only history of MigrationReports per session.
--
-- Before: PRIMARY KEY (session_id) — one report per session, re-runs
-- silently overwrote.  After: PRIMARY KEY (report_id), with each row
-- carrying a monotonic per-session `version`.  Re-runs INSERT a new row
-- with version = max(version)+1; the original row stays.
--
-- The "latest report for a session" query becomes:
--   SELECT … ORDER BY version DESC LIMIT 1   (indexed below).

ALTER TABLE migration_reports
    ADD COLUMN report_id UUID,
    ADD COLUMN version   INTEGER;

-- Backfill existing rows (one per session at this point) as v1.
UPDATE migration_reports
   SET report_id = gen_random_uuid(),
       version   = 1
 WHERE report_id IS NULL;

-- Both new columns are now populated; lock them down.
ALTER TABLE migration_reports
    ALTER COLUMN report_id SET NOT NULL,
    ALTER COLUMN version   SET NOT NULL;

-- Replace the PK so multiple rows per session_id are allowed.
ALTER TABLE migration_reports
    DROP CONSTRAINT migration_reports_pkey,
    ADD  CONSTRAINT migration_reports_pkey PRIMARY KEY (report_id);

-- Guard against duplicate (session_id, version) pairs.
ALTER TABLE migration_reports
    ADD CONSTRAINT uq_migration_reports_session_version UNIQUE (session_id, version);

-- Re-establish the FK that the PK swap dropped.
ALTER TABLE migration_reports
    ADD CONSTRAINT fk_migration_reports_session
    FOREIGN KEY (session_id) REFERENCES workflow_sessions(id) ON DELETE CASCADE;

-- Index for "latest version for a session" — the dominant read path
-- (GET /api/v1/sessions/{id}/report still returns latest).
CREATE INDEX idx_migration_reports_session_version
    ON migration_reports (session_id, version DESC);
