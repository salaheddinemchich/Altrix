package com.altrix.project.domain.port.out;

import com.altrix.project.domain.model.webhook.NewCommitDetected;

/**
 * Driven port — broadcasts webhook-derived domain events (#90).
 *
 * <p>Implementations transport the event off-process; the Kafka adapter is
 * the production binding, but the port lets tests swap in an in-memory
 * collector.
 */
public interface WebhookEventPublisherPort {

    /** Emit a {@link NewCommitDetected} event for downstream services. */
    void publish(NewCommitDetected event);
}
