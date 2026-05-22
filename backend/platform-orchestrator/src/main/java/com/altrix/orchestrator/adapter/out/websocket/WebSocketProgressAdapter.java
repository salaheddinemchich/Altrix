package com.altrix.orchestrator.adapter.out.websocket;

import com.altrix.orchestrator.domain.port.out.ProgressNotifierPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Secondary adapter — broadcasts {@link ProgressEvent} payloads via WebSocket.
 *
 * <p>Clients subscribe to {@code /topic/jobs/{jobId}} to receive real-time
 * agent progress updates. The typed {@link ProgressEvent} record enforces the
 * Angular contract: {@code {jobId, agentName, status, message?, timestamp}}.
 *
 * <p>The event object is passed to {@link SimpMessagingTemplate#convertAndSend}
 * unserialized — Spring's MappingJackson2MessageConverter serializes it as
 * JSON and sets {@code content-type: application/json}.  Passing a pre-
 * serialized String here would route through StringMessageConverter and ship
 * the body as {@code text/plain}, which works in some clients but breaks the
 * Angular STOMP client's JSON parsing path on others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketProgressAdapter implements ProgressNotifierPort {

    private final SimpMessagingTemplate messagingTemplate;

    @Override
    public void notify(String jobId, String agentName, String status, String message) {
        try {
            ProgressEvent event = ProgressEvent.of(jobId, agentName, status, message);
            messagingTemplate.convertAndSend("/topic/jobs/" + jobId, event);

            log.info("Progress sent — job='{}' agent='{}' status='{}'{}",
                    jobId, agentName, status,
                    message != null && !message.isBlank() ? " msg='" + message + "'" : "");

        } catch (Exception e) {
            log.error("Failed to send WebSocket progress for job '{}': {}",
                    jobId, e.getMessage());
        }
    }
}
