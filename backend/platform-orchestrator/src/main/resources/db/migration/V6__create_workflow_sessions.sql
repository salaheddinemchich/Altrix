-- Persists WorkflowSession aggregates; JSONB plan column for schema-evolution safety (#55 #57).
CREATE TABLE IF NOT EXISTS workflow_sessions (
    id           UUID         PRIMARY KEY,
    job_id       VARCHAR(36)  NOT NULL UNIQUE,
    project_id   VARCHAR(36)  NOT NULL,
    status       VARCHAR(30)  NOT NULL,
    plan         JSONB,
    error_message TEXT,
    paused_from  VARCHAR(30),
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ws_status
    ON workflow_sessions (status);

CREATE INDEX IF NOT EXISTS idx_ws_project_id
    ON workflow_sessions (project_id);
