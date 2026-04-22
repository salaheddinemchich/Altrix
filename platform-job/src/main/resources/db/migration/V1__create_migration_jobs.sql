CREATE TABLE IF NOT EXISTS migration_jobs (
    id                       VARCHAR(36)  NOT NULL,
    project_id               VARCHAR(36)  NOT NULL,
    user_id                  VARCHAR(36)  NOT NULL,
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

COMMENT ON TABLE  migration_jobs IS 'Migration jobs tracking PubSub to Kafka migration lifecycle';
COMMENT ON COLUMN migration_jobs.output_storage_key IS 'MinIO key for the migrated output ZIP — populated when DONE';
COMMENT ON COLUMN migration_jobs.error_message      IS 'Human-readable failure reason — populated when FAILED';
