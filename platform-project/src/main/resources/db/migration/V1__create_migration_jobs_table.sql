CREATE TABLE IF NOT EXISTS migration_jobs (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    source_topic VARCHAR(255) NOT NULL,
    target_topic VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
);

CREATE INDEX idx_migration_jobs_status ON migration_jobs(status);
