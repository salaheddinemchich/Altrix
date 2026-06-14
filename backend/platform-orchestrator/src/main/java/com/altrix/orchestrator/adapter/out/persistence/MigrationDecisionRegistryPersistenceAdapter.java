package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.migration.MigrationDecision;
import com.altrix.orchestrator.domain.model.migration.MigrationDecisionRegistry;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.MigrationDecisionRegistryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA adapter for {@link MigrationDecisionRegistryPort}.
 *
 * <p>Append operations do a load → add → save cycle.  Not concurrency-safe
 * across nodes (no row locking) — acceptable because a single session's
 * migration runs on one orchestrator instance, single-threaded through the
 * agent pipeline.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationDecisionRegistryPersistenceAdapter implements MigrationDecisionRegistryPort {

    private final MigrationDecisionsJpaRepository jpaRepository;

    @Override
    public MigrationDecisionRegistry findForSession(WorkflowSessionId sessionId) {
        return jpaRepository.findById(sessionId.value())
                .map(e -> new MigrationDecisionRegistry(
                        sessionId.value().toString(),
                        e.getDecisions() == null ? List.of() : e.getDecisions()))
                .orElseGet(() -> MigrationDecisionRegistry.empty(sessionId.value().toString()));
    }

    @Override
    public void record(WorkflowSessionId sessionId, MigrationDecision decision) {
        recordAll(sessionId, List.of(decision));
    }

    @Override
    public void recordAll(WorkflowSessionId sessionId, List<MigrationDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) return;
        UUID id = sessionId.value();
        List<MigrationDecision> merged = jpaRepository.findById(id)
                .map(e -> new ArrayList<>(e.getDecisions() == null ? List.<MigrationDecision>of() : e.getDecisions()))
                .orElseGet(ArrayList::new);
        merged.addAll(decisions);
        jpaRepository.save(MigrationDecisionsJpaEntity.builder()
                .sessionId(id)
                .decisions(merged)
                .updatedAt(Instant.now())
                .build());
        log.debug("Recorded {} migration decision(s) for session {} ({} total)",
                decisions.size(), id, merged.size());
    }

    @Override
    public void clear(WorkflowSessionId sessionId) {
        jpaRepository.deleteById(sessionId.value());
    }
}
