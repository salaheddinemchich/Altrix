-- ── platform-job: schema hardening & provider-profile indexing ───────────────
--
-- Trade-off notes:
--   1. VARCHAR(36) UUIDs are kept (see V3 reasoning in platform-project).
--   2. provider_profile was added in V3 as a plain column; we add a CHECK
--      constraint and a partial index here rather than in V3 to keep each
--      migration focused on a single concern.
--   3. The partial index on ALL_LOCAL is narrow (few rows) — it lets the
--      routing layer quickly enumerate jobs that may only use local providers.

-- ── Enum integrity guards ─────────────────────────────────────────────────────
-- PostgreSQL does not support `ADD CONSTRAINT IF NOT EXISTS`. Guard with DO blocks.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_jobs_status') THEN
        ALTER TABLE migration_jobs
            ADD CONSTRAINT chk_jobs_status
                CHECK (status IN ('PENDING','ANALYZING','MIGRATING','DONE','FAILED','CANCELLED'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_jobs_config_format_preference') THEN
        ALTER TABLE migration_jobs
            ADD CONSTRAINT chk_jobs_config_format_preference
                CHECK (config_format_preference IN ('KEEP_ORIGINAL','FORCE_YAML','FORCE_PROPERTIES'));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_jobs_provider_profile') THEN
        ALTER TABLE migration_jobs
            ADD CONSTRAINT chk_jobs_provider_profile
                CHECK (provider_profile IN ('DEFAULT','ALL_LOCAL'));
    END IF;
END$$;

-- ── Provider-profile routing index ────────────────────────────────────────────
-- Covers: SELECT … WHERE provider_profile = 'ALL_LOCAL' AND status = ?
-- Partial because ALL_LOCAL is a minority of rows; full index would be wasted.
CREATE INDEX IF NOT EXISTS idx_jobs_local_provider
    ON migration_jobs (status, created_at DESC)
    WHERE provider_profile = 'ALL_LOCAL';

-- ── project-scoped job lookup ─────────────────────────────────────────────────
-- Covers: GET /projects/{id}/jobs — list all jobs for a project
CREATE INDEX IF NOT EXISTS idx_jobs_project_created
    ON migration_jobs (project_id, created_at DESC)
    INCLUDE (id, status, provider_profile);

-- ── Statistics boost ─────────────────────────────────────────────────────────
ALTER TABLE migration_jobs ALTER COLUMN provider_profile SET STATISTICS 100;

ANALYZE migration_jobs;

COMMENT ON CONSTRAINT chk_jobs_status ON migration_jobs
    IS 'DB-level guard against invalid status strings';
