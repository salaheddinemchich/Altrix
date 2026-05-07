package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.session.SessionPauseRecord;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.SessionPauseHistoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionPauseHistoryPersistenceAdapter implements SessionPauseHistoryPort {

    private final SessionPauseHistoryJpaRepository repository;

    @Override
    @Transactional
    public void record(WorkflowSessionId sessionId, SessionStatus pausedFrom, Instant pausedAt) {
        repository.save(SessionPauseHistoryEntity.builder()
                .sessionId(sessionId.value())
                .pausedFrom(pausedFrom)
                .pausedAt(pausedAt)
                .build());
    }

    @Override
    @Transactional
    public void markResumed(WorkflowSessionId sessionId, Instant resumedAt) {
        repository.findLatestOpenEntry(sessionId.value()).ifPresentOrElse(
                entry -> {
                    entry.setResumedAt(resumedAt);
                    repository.save(entry);
                },
                () -> log.warn("No open pause entry found for session '{}' on resume", sessionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SessionPauseRecord> findBySessionId(WorkflowSessionId sessionId) {
        return repository.findBySessionIdOrderByPausedAtDesc(sessionId.value())
                .stream()
                .map(e -> new SessionPauseRecord(
                        e.getId(),
                        sessionId,
                        e.getPausedFrom(),
                        e.getPausedAt(),
                        e.getResumedAt()))
                .toList();
    }
}
