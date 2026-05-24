package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SandboxLogJpaRepository extends JpaRepository<SandboxLogJpaEntity, SandboxLogJpaEntity.Pk> {

    List<SandboxLogJpaEntity> findBySessionIdOrderByRunnerIdAsc(UUID sessionId);

    Optional<SandboxLogJpaEntity> findBySessionIdAndRunnerId(UUID sessionId, String runnerId);
}
