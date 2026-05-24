package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.sandbox.SandboxLog;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.SandboxLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link SandboxLogRepository} via Spring Data JPA.  Save is
 * an upsert keyed on (session_id, runner_id).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxLogPersistenceAdapter implements SandboxLogRepository {

    private final SandboxLogJpaRepository repository;

    @Override
    @Transactional
    public void save(SandboxLog log) {
        try {
            SandboxLogJpaEntity entity = SandboxLogJpaEntity.builder()
                    .sessionId(UUID.fromString(log.sessionId()))
                    .runnerId(log.runnerId())
                    .content(log.content() != null ? log.content() : "")
                    .exitCode(log.exitCode())
                    .generatedAt(log.generatedAt() != null ? log.generatedAt() : Instant.now())
                    .build();
            repository.save(entity);
            SandboxLogPersistenceAdapter.log.debug("Saved sandbox log session='{}' runner='{}' ({} chars)",
                    log.sessionId(), log.runnerId(),
                    log.content() != null ? log.content().length() : 0);
        } catch (Exception e) {
            // Best-effort — never propagate a persistence failure to the
            // validator pipeline.  The findings still flow downstream.
            SandboxLogPersistenceAdapter.log.warn("Could not persist sandbox log for session='{}' runner='{}': {}",
                    log.sessionId(), log.runnerId(), e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<SandboxLog> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findBySessionIdOrderByRunnerIdAsc(sessionId.value())
                .stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SandboxLog> findBySessionIdAndRunnerId(WorkflowSessionId sessionId, String runnerId) {
        return repository.findBySessionIdAndRunnerId(sessionId.value(), runnerId).map(this::toDomain);
    }

    private SandboxLog toDomain(SandboxLogJpaEntity e) {
        return new SandboxLog(
                e.getSessionId().toString(),
                e.getRunnerId(),
                e.getContent(),
                e.getExitCode(),
                e.getGeneratedAt()
        );
    }
}
