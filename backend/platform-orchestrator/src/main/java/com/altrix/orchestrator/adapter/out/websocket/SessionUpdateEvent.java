package com.altrix.orchestrator.adapter.out.websocket;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire-format DTO for session lifecycle events broadcast to WebSocket subscribers.
 *
 * <p>Clients subscribe to {@code /topic/sessions/{sessionId}} to receive real-time
 * session state changes. {@code message} is suppressed when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionUpdateEvent(
        String sessionId,
        String jobId,
        SessionStatus status,
        String message,
        Instant timestamp
) {
    public static SessionUpdateEvent of(WorkflowSessionId sessionId, String jobId,
                                        SessionStatus status, String message) {
        return new SessionUpdateEvent(
                sessionId.value().toString(), jobId, status, message, Instant.now());
    }
}
