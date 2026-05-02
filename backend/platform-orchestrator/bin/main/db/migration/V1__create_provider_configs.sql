CREATE TABLE IF NOT EXISTS provider_configs (
    provider_id       VARCHAR(50)  NOT NULL,
    enabled           BOOLEAN,
    encrypted_api_key TEXT,
    base_url          VARCHAR(500),
    model_analysis    VARCHAR(200),
    model_migration   VARCHAR(200),
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_provider_configs PRIMARY KEY (provider_id)
);
