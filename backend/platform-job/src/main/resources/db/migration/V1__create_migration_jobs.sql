CREATE TABLE IF NOT EXISTS migration_jobs (
    id                       VARCHAR(36)  NOT NULL,
    project_id               VARCHAR(36)  NOT NULL,
    user_id                  VARCHAR(36)  NOT NULL,
    project_storage_key      TEXT,
    status                   VARCHAR(20)  NOT NULL,
    config_format_preference VARCHAR(20)  NOT NULL DEFAULT 'KEEP_ORIGINAL',
    output_storage_key       TEXT,
    error_message            TEXT,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    completed_at             TIMESTAMPTZ,
    CONSTRAINT pk_migration_jobs PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_jobs_user_id    ON migration_jobs (user_id);
CREATE INDEX IF NOT EXISTS idx_jobs_project_id ON migration_jobs (project_id);
CREATE INDEX IF NOT EXISTS idx_jobs_status     ON migration_jobs (status);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON migration_jobs (created_at DESC);
