package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MigrationReportJpaRepository extends JpaRepository<MigrationReportJpaEntity, UUID> {

    /** Latest report (max version) for a session — feeds GET /sessions/{id}/report. */
    Optional<MigrationReportJpaEntity> findFirstBySessionIdOrderByVersionDesc(UUID sessionId);

    /** Full append-only history, newest version first — feeds GET /sessions/{id}/reports. */
    List<MigrationReportJpaEntity> findBySessionIdOrderByVersionDesc(UUID sessionId);

    /** Specific version — feeds GET /sessions/{id}/reports/{version}. */
    Optional<MigrationReportJpaEntity> findBySessionIdAndVersion(UUID sessionId, int version);

    /**
     * Current max version for a session, 0 when none exist.  Used to
     * compute the next-version number on append.
     */
    @Query("SELECT COALESCE(MAX(r.version), 0) FROM MigrationReportJpaEntity r WHERE r.sessionId = :sessionId")
    int maxVersion(UUID sessionId);
}
