package com.altrix.project.adapter.out.persistence;

import com.altrix.project.domain.model.webhook.WebhookProcessingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * JPA entity persisting every incoming GitHub webhook for audit + replay (#91).
 *
 * <p>{@code payload} is the raw request body as received off the wire, stored
 * in a {@code jsonb} column so PostgreSQL JSON operators (e.g.
 * {@code payload->'repository'->>'full_name'}) work without re-parsing.
 */
@Entity
@Table(name = "webhook_deliveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookDeliveryEntity {

    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id;

    /** Value of the {@code X-GitHub-Delivery} header — GitHub's UUID for this event. */
    @Column(name = "delivery_id", nullable = false, length = 64)
    private String deliveryId;

    /** Value of the {@code X-GitHub-Event} header, e.g. {@code "push"} or {@code "pull_request"}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 16)
    private WebhookProcessingStatus processingStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;
}
