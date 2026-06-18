package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.common.domain.model.MigrationReport;
import com.altrix.orchestrator.domain.model.report.MigrationReportEntry;
import com.altrix.orchestrator.domain.model.report.ReportSearchHit;
import com.altrix.orchestrator.domain.model.report.ReportSearchPage;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
                .structuredSummary(report.summary())
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

    @Override
    @Transactional(readOnly = true)
    public ReportSearchPage search(String query, int page, int size) {
        // Empty / blank query short-circuits to an empty page — Postgres
        // would return everything on '' which is rarely useful and burns
        // index reads.
        if (query == null || query.isBlank()) {
            return new ReportSearchPage(List.of(), page, size, 0L, 0);
        }
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        Page<MigrationReportSearchHit> result =
                repository.searchByQuery(query, PageRequest.of(safePage, safeSize));

        List<ReportSearchHit> hits = result.getContent().stream()
                .map(h -> new ReportSearchHit(
                        h.getReportId(), h.getSessionId(),
                        h.getVersion() != null ? h.getVersion() : 0,
                        h.getProjectId(),
                        h.getGeneratedAt(),
                        h.getSnippet() != null ? h.getSnippet() : "",
                        h.getRank() != null ? h.getRank() : 0f))
                .toList();
        return new ReportSearchPage(
                hits, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
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
        return new MigrationReport(e.getProjectId(), e.getContent(), e.getGeneratedAt(), e.getStructuredSummary());
    }
}
