-- Issue #90 follow-up — let webhook consumers find the Project that owns a repo URL.
--
-- repo_url       full HTTPS clone URL (https://host/owner/name.git)
-- tracked_branch branch checked out at clone time (defaults to "main" on existing rows)
-- source         where this project came from — MANUAL (legacy /upload),
--                GIT_CLONE (manual /clone), or WEBHOOK (auto-triggered)
--
-- All three columns are nullable on backfill so existing rows do not break;
-- new INSERT paths populate them.  A partial index on repo_url speeds up the
-- webhook consumer's lookup, ignoring legacy rows where repo_url is NULL.

ALTER TABLE projects
    ADD COLUMN repo_url       VARCHAR(512),
    ADD COLUMN tracked_branch VARCHAR(255),
    ADD COLUMN source         VARCHAR(16);

CREATE INDEX idx_projects_repo_url
    ON projects (repo_url)
    WHERE repo_url IS NOT NULL;
