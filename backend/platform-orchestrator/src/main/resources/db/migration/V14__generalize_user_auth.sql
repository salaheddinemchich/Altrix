-- ──────────────────────────────────────────────────────────────────────────
-- Auth refactor: generalize the user model.
--
-- Drops the GitHub-specific users table and recreates it provider-agnostic.
-- All provider identity (GitHub, GitLab, …) moves to user_auth_providers, so
-- a single user can authenticate through multiple providers.
-- ──────────────────────────────────────────────────────────────────────────

-- Drop in FK order: refresh_tokens depends on users.github_id
DROP TABLE IF EXISTS refresh_tokens CASCADE;
DROP TABLE IF EXISTS users          CASCADE;

CREATE TABLE users (
    id            VARCHAR(36)  PRIMARY KEY,
    email         VARCHAR(200),
    display_name  VARCHAR(200),
    avatar_url    VARCHAR(500),
    role          VARCHAR(20)  NOT NULL DEFAULT 'ROLE_USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users (email);

-- Per-provider identity link. (provider_type, provider_id) is the unique
-- external identity coordinate; a user_id may have several rows.
CREATE TABLE user_auth_providers (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                VARCHAR(36)  NOT NULL,
    provider_type          VARCHAR(20)  NOT NULL,           -- GITHUB | GITLAB
    provider_id            VARCHAR(100) NOT NULL,           -- stable provider identifier
    provider_login         VARCHAR(200),
    encrypted_access_token TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_uap_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_uap_provider UNIQUE (provider_type, provider_id)
);

CREATE INDEX idx_uap_user_id ON user_auth_providers (user_id);

COMMENT ON COLUMN user_auth_providers.encrypted_access_token
    IS 'AES-256-GCM encrypted OAuth access token — used for provider API calls.';

-- Recreate refresh_tokens keyed on the internal user UUID
CREATE TABLE refresh_tokens (
    id               BIGSERIAL    PRIMARY KEY,
    token_hash       VARCHAR(64)  NOT NULL UNIQUE,
    user_id          VARCHAR(36)  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked          BOOLEAN      NOT NULL DEFAULT FALSE,
    replaced_by_hash VARCHAR(64),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_hash    ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
