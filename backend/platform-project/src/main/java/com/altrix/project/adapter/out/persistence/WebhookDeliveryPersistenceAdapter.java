package com.altrix.project.adapter.out.persistence;

import com.altrix.project.domain.model.webhook.WebhookDelivery;
import com.altrix.project.domain.port.out.WebhookDeliveryRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Maps between the domain {@link WebhookDelivery} value object and the JPA
 * {@link WebhookDeliveryEntity}, hiding Hibernate behind a domain port (#91).
 */
@Component
@RequiredArgsConstructor
public class WebhookDeliveryPersistenceAdapter implements WebhookDeliveryRepositoryPort {

    private final WebhookDeliveryJpaRepository jpa;

    @Override
    public WebhookDelivery save(WebhookDelivery delivery) {
        WebhookDeliveryEntity entity = toEntity(delivery);
        WebhookDeliveryEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<WebhookDelivery> findByDeliveryId(String deliveryId) {
        return jpa.findByDeliveryId(deliveryId).map(WebhookDeliveryPersistenceAdapter::toDomain);
    }

    @Override
    public int deleteAllReceivedBefore(Instant cutoff) {
        return jpa.deleteAllReceivedBefore(cutoff);
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private static WebhookDeliveryEntity toEntity(WebhookDelivery d) {
        return WebhookDeliveryEntity.builder()
                .id(d.id())
                .deliveryId(d.deliveryId())
                .eventType(d.eventType())
                .signatureValid(d.signatureValid())
                .processingStatus(d.processingStatus())
                .payload(d.payload())
                .errorMessage(d.errorMessage())
                .receivedAt(d.receivedAt())
                .processedAt(d.processedAt())
                .replayOf(d.replayOf())
                .build();
    }

    private static WebhookDelivery toDomain(WebhookDeliveryEntity e) {
        return new WebhookDelivery(
                e.getId(),
                e.getDeliveryId(),
                e.getEventType(),
                e.isSignatureValid(),
                e.getProcessingStatus(),
                e.getPayload(),
                e.getErrorMessage(),
                e.getReceivedAt(),
                e.getProcessedAt(),
                e.getReplayOf()
        );
    }
}
