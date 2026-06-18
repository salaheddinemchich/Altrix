-- Structured, machine-readable companion to migration_reports.content.
-- Nullable so existing rows (generated before the deterministic report
-- generator rewrite) remain valid without a backfill.
ALTER TABLE migration_reports
    ADD COLUMN structured_summary JSONB DEFAULT NULL;
