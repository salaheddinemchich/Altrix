package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.DecisionKind;
import com.altrix.orchestrator.domain.model.session.SessionAggregate;
import com.altrix.orchestrator.domain.model.session.SessionPage;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

    @Override
    @Transactional(readOnly = true)
    public SessionPage findAll(int page, int size, String sortBy, boolean descending, SessionStatus statusFilter) {
        Sort sort = descending ? Sort.by(sortBy).descending() : Sort.by(sortBy).ascending();
        PageRequest pageable = PageRequest.of(page, size, sort);

        // Use the projection query — excludes plan/migrated_files JSONB columns,
        // cutting per-page data transfer by ~90% for list views.
        Page<SessionSummaryProjection> result = statusFilter != null
                ? repository.findProjectedByStatus(statusFilter, pageable)
                : repository.findProjectedBy(pageable);

        return new SessionPage(
                result.getContent().stream().map(this::projectionToDomain).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Override
    @Transactional
    public void deleteById(WorkflowSessionId id) {
        repository.deleteById(id.value());
    }

    @Override
    @Transactional(readOnly = true)
    public SessionAggregate aggregate() {
        Map<SessionStatus, Long> byStatus = new EnumMap<>(SessionStatus.class);
        for (SessionStatus s : SessionStatus.values()) byStatus.put(s, 0L);
        for (Object[] row : repository.countByStatus()) {
            byStatus.put((SessionStatus) row[0], ((Number) row[1]).longValue());
        }
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long done = byStatus.getOrDefault(SessionStatus.DONE, 0L);
        Double avgRaw = repository.averageDoneDurationSeconds();
        return new SessionAggregate(total, done, avgRaw != null ? avgRaw : 0.0, byStatus);
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private WorkflowSessionJpaEntity toEntity(WorkflowSession s) {
        return WorkflowSessionJpaEntity.builder()
                .id(s.id().value())
                .version(s.version())
                .jobId(s.jobId())
                .projectId(s.projectId())
                .status(s.status())
                .plan(s.plan())
                .errorMessage(s.errorMessage())
                .pausedFrom(s.pausedFrom())
                .consecutiveAgentErrors(s.consecutiveAgentErrors())
                .migratedFiles(s.migratedFiles().isEmpty() ? null : s.migratedFiles())
                .createdAt(s.createdAt())
                .updatedAt(s.updatedAt())
                .decidedBy(s.decidedBy())
                .decidedAt(s.decidedAt())
                .decisionKind(s.decisionKind() != null ? s.decisionKind().name() : null)
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
                e.getConsecutiveAgentErrors(),
                e.getMigratedFiles(),
                e.getCreatedAt(),
                e.getVersion() != null ? e.getVersion() : 0L,
                e.getDecidedBy(),
                e.getDecidedAt(),
                e.getDecisionKind() != null ? DecisionKind.valueOf(e.getDecisionKind()) : null);
    }

    private WorkflowSession projectionToDomain(SessionSummaryProjection p) {
        return new WorkflowSession(
                WorkflowSessionId.of(p.getId()),
                p.getJobId(),
                p.getProjectId(),
                p.getStatus(),
                p.getPlan(),  // included so the sessions UI can render the plan-preview row (#10)
                null,         // errorMessage excluded from list projection
                p.getPausedFrom(),
                p.getConsecutiveAgentErrors(),
                List.of(),    // migratedFiles excluded from list projection
                p.getCreatedAt(),
                0L);
    }
}
