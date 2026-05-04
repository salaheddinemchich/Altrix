-- Composite index: user + status — covers the most common list query:
-- "show me all PENDING/COMPLETED projects for user X"
CREATE INDEX IF NOT EXISTS idx_projects_user_status
    ON projects (user_id, status);

-- Partial index: active projects only — much smaller, used for dashboard polling
CREATE INDEX IF NOT EXISTS idx_projects_active
    ON projects (user_id, created_at DESC)
    WHERE status NOT IN ('COMPLETED', 'FAILED');

-- Covering index: list endpoint returns id + name + status + created_at
-- Index-only scan avoids touching the heap for the list query
CREATE INDEX IF NOT EXISTS idx_projects_list_covering
    ON projects (user_id, created_at DESC)
    INCLUDE (id, name, status, framework, build_system);

-- Update planner statistics for better query plans on enum-like columns
ALTER TABLE projects ALTER COLUMN status       SET STATISTICS 100;
ALTER TABLE projects ALTER COLUMN framework    SET STATISTICS 100;
ALTER TABLE projects ALTER COLUMN build_system SET STATISTICS 100;

ANALYZE projects;

COMMENT ON INDEX idx_projects_user_status   IS 'Covers: SELECT … WHERE user_id=? AND status=?';
COMMENT ON INDEX idx_projects_active        IS 'Covers dashboard polling — active projects only';
COMMENT ON INDEX idx_projects_list_covering IS 'Index-only scan for project list endpoint';
