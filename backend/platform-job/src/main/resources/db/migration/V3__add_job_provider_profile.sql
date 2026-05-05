-- Per-job AI provider profile override (#52).
-- DEFAULT = follows org plan; ALL_LOCAL = forces FREE-tier providers only.
ALTER TABLE migration_jobs
    ADD COLUMN IF NOT EXISTS provider_profile VARCHAR(20) NOT NULL DEFAULT 'DEFAULT';
