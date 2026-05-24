package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationReport;
import com.altrix.orchestrator.domain.model.report.MigrationReportEntry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link MigrationReportRepository} via Spring Data JPA.
 *
 * <p>Append-only since #162.  {@link #save} computes the next per-session
 * version inside the same transaction so concurrent re-runs of the same
 * session each get a unique row.  The {@code (session_id, version)}
 * UNIQUE constraint in V17 is the ultimate safety net — a race would
 * surface as a DataIntegrityViolation rather than silently overwriting.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationReportPersistenceAdapter implements MigrationReportRepository {

    private final MigrationReportJpaRepository repository;

    @Override
    @Transactional
    public MigrationReportEntry save(WorkflowSessionId sessionId, MigrationReport report) {
        int nextVersion = repository.maxVersion(sessionId.value()) + 1;
        UUID reportId = UUID.randomUUID();
        MigrationReportJpaEntity entity = MigrationReportJpaEntity.builder()
                .reportId(reportId)
                .sessionId(sessionId.value())
                .version(nextVersion)
                .projectId(report.projectId())
                .content(report.content())
                .generatedAt(report.generatedAt() != null ? report.generatedAt() : Instant.now())
                .build();
        repository.save(entity);
        log.debug("Saved migration report v{} for session '{}' ({} chars)",
                nextVersion, sessionId, report.content().length());
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MigrationReport> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findFirstBySessionIdOrderByVersionDesc(sessionId.value())
                .map(this::toMigrationReport);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MigrationReportEntry> findAllBySessionId(WorkflowSessionId sessionId) {
        return repository.findBySessionIdOrderByVersionDesc(sessionId.value())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MigrationReportEntry> findBySessionIdAndVersion(WorkflowSessionId sessionId, int version) {
        return repository.findBySessionIdAndVersion(sessionId.value(), version)
                .map(this::toDomain);
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    private MigrationReportEntry toDomain(MigrationReportJpaEntity e) {
        return new MigrationReportEntry(
                e.getReportId(),
                e.getSessionId(),
                e.getVersion(),
                toMigrationReport(e)
        );
    }

    private MigrationReport toMigrationReport(MigrationReportJpaEntity e) {
        return new MigrationReport(e.getProjectId(), e.getContent(), e.getGeneratedAt());
    }
}
