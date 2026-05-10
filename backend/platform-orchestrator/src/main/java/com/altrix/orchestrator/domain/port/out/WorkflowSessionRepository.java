package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.session.SessionPage;
import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSession;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Secondary port — persists and queries {@link WorkflowSession} aggregates.
 *
 * <p>No Spring or JPA types cross this boundary; domain services depend only
 * on this interface and remain framework-free.
 */
public interface WorkflowSessionRepository {

    /**
     * Persists a new or updated session and drains + publishes its domain events.
     */
    WorkflowSession save(WorkflowSession session);

    Optional<WorkflowSession> findById(WorkflowSessionId id);

    /**
     * Looks up the session created for a given job.
     */
    Optional<WorkflowSession> findByJobId(String jobId);

    List<WorkflowSession> findByStatus(SessionStatus status);

    /**
     * Returns sessions in the given status whose {@code updatedAt} is before the cutoff — used by the approval-timeout scheduler (#68).
     */
    List<WorkflowSession> findByStatusAndUpdatedAtBefore(SessionStatus status, Instant before);

    /**
     * Paginated list of all sessions, optionally filtered by status (#117).
     */
    SessionPage findAll(int page, int size, String sortBy, boolean descending, SessionStatus statusFilter);
}
