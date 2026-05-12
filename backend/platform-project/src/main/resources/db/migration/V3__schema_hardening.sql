-- ── platform-project: schema hardening & covering-index tuning ───────────────
--
-- Trade-off notes:
--   1. VARCHAR(36) for id/user_id is kept for Java String compatibility.
--      UUID type (16 B vs 36 B) would save ~90 MB per 2 M rows but requires
--      application-layer changes to JPA entities. Deferred to a future sprint.
--   2. build_system / config_format / framework remain NULLable — they are
--      populated asynchronously by the detection phase; NOT NULL would require
--      DEFAULT placeholders that misrepresent undetected state.
--   3. We add CHECK constraints to guard enum columns at the DB level in
--      addition to the application-level @Enumerated(EnumType.STRING).

-- ── Enum integrity guards ─────────────────────────────────────────────────────
-- PostgreSQL does not support `ADD CONSTRAINT IF NOT EXISTS`. Guard with DO blocks.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_projects_status'
    ) THEN
        ALTER TABLE projects
            ADD CONSTRAINT chk_projects_status
                CHECK (status IN ('PENDING','READY','ERROR','COMPLETED','FAILED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_projects_config_format_preference'
    ) THEN
        ALTER TABLE projects
            ADD CONSTRAINT chk_projects_config_format_preference
                CHECK (config_format_preference IN ('KEEP_ORIGINAL','FORCE_YAML','FORCE_PROPERTIES'));
    END IF;
END$$;

-- ── Coverage gap: find projects by user + framework (e.g. "all Spring Boot jobs") ──
-- This is a low-cardinality secondary filter; a partial index keeps it small.
CREATE INDEX IF NOT EXISTS idx_projects_user_framework
    ON projects (user_id, framework)
    WHERE framework IS NOT NULL;

-- ── Statistics boost ─────────────────────────────────────────────────────────
ALTER TABLE projects ALTER COLUMN config_format_preference SET STATISTICS 200;
ALTER TABLE projects ALTER COLUMN config_format            SET STATISTICS 100;

ANALYZE projects;

COMMENT ON CONSTRAINT chk_projects_status ON projects
    IS 'DB-level guard: prevents out-of-enum status strings surviving ORM bugs';
