-- Issue #92 — Webhook replay endpoint.
--
-- Adds a self-referential FK so a replayed delivery row links back to the
-- original it replays.  ON DELETE SET NULL keeps replay rows intact when the
-- nightly purge eventually removes the source row outside the retention window.

ALTER TABLE webhook_deliveries
    ADD COLUMN replay_of VARCHAR(36)
        REFERENCES webhook_deliveries (id)
        ON DELETE SET NULL;

CREATE INDEX idx_webhook_deliveries_replay_of
    ON webhook_deliveries (replay_of)
    WHERE replay_of IS NOT NULL;
