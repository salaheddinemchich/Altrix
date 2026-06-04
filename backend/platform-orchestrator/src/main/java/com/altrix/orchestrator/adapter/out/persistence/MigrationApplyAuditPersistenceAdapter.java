package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.apply.MigrationApplyAuditEntry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationApplyAuditLogPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationApplyAuditPersistenceAdapter implements MigrationApplyAuditLogPort {

    private final MigrationApplyAuditJpaRepository repository;

    @Override
    @Transactional
    public void save(MigrationApplyAuditEntry entry) {
        try {
            repository.save(MigrationApplyAuditJpaEntity.builder()
                    .id(UUID.randomUUID())
                    .sessionId(entry.sessionId().value())
                    .actorUserId(entry.actorUserId())
                    .eventType(entry.eventType())
                    .payload(entry.payload())
                    .occurredAt(entry.occurredAt() != null ? entry.occurredAt() : Instant.now())
                    .build());
        } catch (Exception e) {
            // Audit failure must never block the apply workflow itself.
            log.warn("Apply-audit save failed for session={} actor={} event={}: {}",
                    entry.sessionId(), entry.actorUserId(), entry.eventType(), e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<MigrationApplyAuditEntry> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findBySessionIdOrderByOccurredAtAsc(sessionId.value()).stream()
                .map(this::toDomain)
                .toList();
    }

    private MigrationApplyAuditEntry toDomain(MigrationApplyAuditJpaEntity e) {
        return new MigrationApplyAuditEntry(
                new WorkflowSessionId(e.getSessionId()),
                e.getActorUserId(),
                e.getEventType(),
                e.getPayload(),
                e.getOccurredAt());
    }
}
