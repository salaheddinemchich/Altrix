-- Persists GitHub-authenticated users (#81 #82).
-- The access token is stored AES-256-GCM encrypted (same key as provider_configs).
CREATE TABLE IF NOT EXISTS users (
    id                     BIGSERIAL     PRIMARY KEY,
    github_id              VARCHAR(50)   NOT NULL,
    github_login           VARCHAR(100)  NOT NULL,
    email                  VARCHAR(200),
    display_name           VARCHAR(200),
    avatar_url             VARCHAR(500),
    encrypted_access_token TEXT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_github_id ON users (github_id);

COMMENT ON COLUMN users.encrypted_access_token
    IS 'AES-256-GCM encrypted GitHub OAuth access token — used for private repo cloning (#82)';
