package com.altrix.orchestrator.adapter.out.websocket;

import com.altrix.orchestrator.domain.model.session.SessionStatus;
import com.altrix.orchestrator.domain.model.session.WorkflowSessionId;
import com.altrix.orchestrator.domain.port.out.SessionProgressPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Secondary adapter — broadcasts {@link SessionUpdateEvent} payloads via STOMP WebSocket (#60).
 *
 * <p>Clients subscribe to {@code /topic/sessions/{sessionId}} to receive session
 * lifecycle transitions (MIGRATING, DONE, FAILED, PAUSED, etc.).
 *
 * <p>Errors are caught and logged rather than propagated — a WebSocket failure
 * must never roll back a successful DB transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionProgressPublisher implements SessionProgressPort {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publishSessionUpdate(WorkflowSessionId sessionId, String jobId,
                                     SessionStatus status, String message) {
        try {
            SessionUpdateEvent event = SessionUpdateEvent.of(sessionId, jobId, status, message);
            String json = objectMapper.writeValueAsString(event);
            messagingTemplate.convertAndSend("/topic/sessions/" + sessionId.value(), json);
            log.debug("Session update sent — session='{}' status='{}'", sessionId, status);
        } catch (Exception e) {
            log.error("Failed to publish session update for session '{}': {}",
                    sessionId, e.getMessage());
        }
    }
}
