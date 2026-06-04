package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry.EventType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * JPA mapping of one append-only row of the apply audit log.
 * Payload is JSONB ⇄ {@code Map<String,String>} so we don't proliferate
 * one-off shapes per event type.
 */
@Entity
@Table(name = "migration_apply_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MigrationApplyAuditJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "actor_user_id", nullable = false, length = 64)
    private String actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Convert(converter = StringMapJsonConverter.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
