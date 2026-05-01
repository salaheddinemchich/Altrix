package com.migrator.orchestrator.adapter.out.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.migrator.orchestrator.domain.port.out.ProgressNotifierPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Secondary adapter — broadcasts progress events via WebSocket.
 *
 * <p>Clients subscribe to {@code /topic/jobs/{jobId}} to receive
 * real-time agent progress updates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketProgressAdapter implements ProgressNotifierPort {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper          objectMapper;

    @Override
    public void notify(String jobId, String agentName, String status, String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("jobId",     jobId);
            payload.put("agentName", agentName);
            payload.put("status",    status);
            payload.put("message",   message);

            String json = objectMapper.writeValueAsString(payload);
            messagingTemplate.convertAndSend("/topic/jobs/" + jobId, json);

            log.debug("Progress sent — job='{}' agent='{}' status='{}'",
                    jobId, agentName, status);

        } catch (Exception e) {
            log.error("Failed to send WebSocket progress for job '{}': {}",
                    jobId, e.getMessage());
        }
    }
}
