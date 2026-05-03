CREATE TABLE IF NOT EXISTS projects (
    id                       VARCHAR(36)  NOT NULL,
    user_id                  VARCHAR(36)  NOT NULL,
    name                     VARCHAR(255) NOT NULL,
    storage_key              TEXT         NOT NULL,
    status                   VARCHAR(20)  NOT NULL,
    build_system             VARCHAR(20),
    config_format            VARCHAR(20),
    framework                VARCHAR(20),
    config_format_preference VARCHAR(20)  NOT NULL DEFAULT 'KEEP_ORIGINAL',
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_projects PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_projects_user_id    ON projects (user_id);
CREATE INDEX IF NOT EXISTS idx_projects_status     ON projects (status);
CREATE INDEX IF NOT EXISTS idx_projects_created_at ON projects (created_at DESC);

COMMENT ON TABLE  projects                        IS 'Uploaded projects awaiting or undergoing Altrix migration';
COMMENT ON COLUMN projects.storage_key            IS 'MinIO object key where the uploaded ZIP is stored';
COMMENT ON COLUMN projects.config_format_preference IS 'User preference: KEEP_ORIGINAL | FORCE_YAML | FORCE_PROPERTIES';
