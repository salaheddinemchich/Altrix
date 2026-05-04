-- Composite index: user + status — covers "show all PENDING/ANALYZING jobs for user X"
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_user_status
    ON migration_jobs (user_id, status);

-- Partial index: active jobs only — dashboard polling (excludes terminal states)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_active
    ON migration_jobs (user_id, created_at DESC)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- Covering index: list endpoint returns id + project_id + status + created_at
-- Index-only scan avoids touching the heap for the list query
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jobs_list_covering
    ON migration_jobs (user_id, created_at DESC)
    INCLUDE (id, project_id, status, config_format_preference);

-- Update planner statistics for better query plans on enum-like columns
ALTER TABLE migration_jobs ALTER COLUMN status                   SET STATISTICS 100;
ALTER TABLE migration_jobs ALTER COLUMN config_format_preference SET STATISTICS 100;

ANALYZE migration_jobs;

COMMENT ON INDEX idx_jobs_user_status   IS 'Covers: SELECT … WHERE user_id=? AND status=?';
COMMENT ON INDEX idx_jobs_active        IS 'Dashboard polling — active jobs only';
COMMENT ON INDEX idx_jobs_list_covering IS 'Index-only scan for job list endpoint';
