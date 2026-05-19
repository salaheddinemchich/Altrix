-- Issue #91 — Webhook delivery audit log.
--
-- Every incoming webhook (regardless of signature outcome) is persisted here
-- before any business processing happens.  This gives operators a permanent
-- audit trail and a replay source when downstream processing fails.
--
-- payload is stored as JSONB so existing GitHub event keys
-- (action, repository.full_name, ref, head_commit.id, …) remain queryable
-- via standard PostgreSQL JSON operators without re-parsing.

CREATE TABLE webhook_deliveries (
    id                  VARCHAR(36)  PRIMARY KEY,
    delivery_id         VARCHAR(64)  NOT NULL,                -- X-GitHub-Delivery header
    event_type          VARCHAR(64)  NOT NULL,                -- X-GitHub-Event header
    signature_valid     BOOLEAN      NOT NULL,
    processing_status   VARCHAR(16)  NOT NULL,                -- ACCEPTED | SKIPPED | FAILED
    payload             JSONB        NOT NULL,
    error_message       TEXT,
    received_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    processed_at        TIMESTAMPTZ
);

-- Lookups by GitHub delivery id (replay endpoint, deduplication)
CREATE UNIQUE INDEX idx_webhook_deliveries_delivery_id
    ON webhook_deliveries (delivery_id);

-- Time-bound purge in WebhookDeliveryPurgeScheduler — needs a btree index on
-- received_at to keep the nightly DELETE off a sequential scan.
CREATE INDEX idx_webhook_deliveries_received_at
    ON webhook_deliveries (received_at);

-- Fast filtering by status when surfacing failed deliveries in the UI.
CREATE INDEX idx_webhook_deliveries_status
    ON webhook_deliveries (processing_status);
