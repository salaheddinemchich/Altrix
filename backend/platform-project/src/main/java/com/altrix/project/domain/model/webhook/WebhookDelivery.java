package com.altrix.project.domain.model.webhook;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable record of a single inbound webhook delivery (#91).
 *
 * <p>Domain value object — has no JPA, no Spring, no JSON.  The persistence
 * adapter maps this to {@code WebhookDeliveryEntity} before writing to the
 * database.
 */
public record WebhookDelivery(
        String id,
        String deliveryId,
        String eventType,
        boolean signatureValid,
        WebhookProcessingStatus processingStatus,
        String payload,
        String errorMessage,
        Instant receivedAt,
        Instant processedAt,
        /** Issue #92 — id of the original delivery this row replays, or null. */
        String replayOf
) {
    public WebhookDelivery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(processingStatus, "processingStatus");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
