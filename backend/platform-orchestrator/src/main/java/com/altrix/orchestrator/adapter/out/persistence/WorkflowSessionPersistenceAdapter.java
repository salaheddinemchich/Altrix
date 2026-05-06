package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Implements {@link WorkflowSessionRepository} using Spring Data JPA (#55).
 *
 * <p>After every successful {@link #save}, domain events collected inside the
 * aggregate are drained and published via {@link ApplicationEventPublisher} (#56).
 * Downstream listeners use {@code @TransactionalEventListener(phase=AFTER_COMMIT)}
 * so no event is dispatched if the transaction rolls back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowSessionPersistenceAdapter implements WorkflowSessionRepository {

    private final WorkflowSessionJpaRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public WorkflowSession save(WorkflowSession session) {
        WorkflowSessionJpaEntity entity = toEntity(session);
        entity = repository.save(entity);
        WorkflowSession saved = toDomain(entity);

        // Drain and publish events — listeners fire AFTER_COMMIT, so nothing
        // is dispatched if this transaction is later rolled back.
        session.drainEvents().forEach(event -> {
            log.debug("Publishing domain event {} for session '{}'",
                    event.getClass().getSimpleName(), session.id());
            eventPublisher.publishEvent(event);
        });

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowSession> findById(WorkflowSessionId id) {
        return repository.findById(id.value()).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkflowSession> findByJobId(String jobId) {
        return repository.findByJobId(jobId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowSession> findByStatus(SessionStatus status) {
        return repository.findByStatus(status).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<WorkflowSession> findByStatusAndUpdatedAtBefore(SessionStatus status, Instant before) {
        return repository.findByStatusAndUpdatedAtBefore(status, before)
                .stream().map(this::toDomain).toList();
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private WorkflowSessionJpaEntity toEntity(WorkflowSession s) {
        return WorkflowSessionJpaEntity.builder()
                .id(s.id().value())
                .jobId(s.jobId())
                .projectId(s.projectId())
                .status(s.status())
                .plan(s.plan())
                .errorMessage(s.errorMessage())
                .pausedFrom(s.pausedFrom())
                .createdAt(s.createdAt())
                .updatedAt(s.updatedAt())
                .build();
    }

    private WorkflowSession toDomain(WorkflowSessionJpaEntity e) {
        return new WorkflowSession(
                WorkflowSessionId.of(e.getId()),
                e.getJobId(),
                e.getProjectId(),
                e.getStatus(),
                e.getPlan(),
                e.getErrorMessage(),
                e.getPausedFrom(),
                e.getCreatedAt());
    }
}
