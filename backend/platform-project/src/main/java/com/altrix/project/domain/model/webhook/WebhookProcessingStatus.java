package com.altrix.project.domain.model.webhook;

/**
 * Lifecycle status of a persisted webhook delivery (#91).
 *
 * <p>Every incoming webhook lands in {@code webhook_deliveries} with one of
 * these values so operators can audit what happened to a payload.
 */
public enum WebhookProcessingStatus {

    /** Signature valid, event recognised, downstream handler ran without error. */
    ACCEPTED,

    /** Signature valid but the event is one we intentionally do not act on
     *  (unknown type, branch we do not track, autoMigrate disabled, …). */
    SKIPPED,

    /** Signature invalid OR downstream handler threw — see {@code error_message}. */
    FAILED
}
