-- #RBAC: add role column to users table (default USER for all existing rows)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'ROLE_USER';

-- #RefreshTokens: store hashed refresh tokens for rotation and revocation
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id               BIGSERIAL PRIMARY KEY,
    token_hash       VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 hex of the raw token
    github_id        VARCHAR(50)  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked          BOOLEAN      NOT NULL DEFAULT FALSE,
    replaced_by_hash VARCHAR(64),                    -- rotation chain (nullable)
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (github_id) REFERENCES users(github_id)
        ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash     ON refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_github   ON refresh_tokens(github_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires  ON refresh_tokens(expires_at);
