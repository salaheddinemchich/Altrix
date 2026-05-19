package com.altrix.project.domain.port.out;

import com.altrix.project.domain.model.webhook.WebhookDelivery;

import java.time.Instant;
import java.util.Optional;

/**
 * Driven port — persists {@link WebhookDelivery} audit records (#91).
 *
 * <p>Keeps the {@code WebhookController} and {@code WebhookDeliveryPurgeScheduler}
 * decoupled from the JPA layer; the ArchUnit hexagonal rule forbids inbound
 * adapters from reaching directly into {@code adapter.out.persistence}.
 */
public interface WebhookDeliveryRepositoryPort {

    /** Insert or update — used by the receiver controller after every delivery. */
    WebhookDelivery save(WebhookDelivery delivery);

    /** Replay / deduplication lookup. */
    Optional<WebhookDelivery> findByDeliveryId(String deliveryId);

    /**
     * Removes every record whose {@code receivedAt} is strictly before
     * {@code cutoff}.  Returns the number of rows affected.
     */
    int deleteAllReceivedBefore(Instant cutoff);
}
