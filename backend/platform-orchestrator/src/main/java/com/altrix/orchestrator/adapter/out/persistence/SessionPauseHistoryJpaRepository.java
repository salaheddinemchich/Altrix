package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionPauseHistoryJpaRepository extends JpaRepository<SessionPauseHistoryEntity, Long> {

    List<SessionPauseHistoryEntity> findBySessionIdOrderByPausedAtDesc(UUID sessionId);

    @Query("SELECT e FROM SessionPauseHistoryEntity e WHERE e.sessionId = :sessionId AND e.resumedAt IS NULL ORDER BY e.pausedAt DESC LIMIT 1")
    Optional<SessionPauseHistoryEntity> findLatestOpenEntry(@Param("sessionId") UUID sessionId);
}
