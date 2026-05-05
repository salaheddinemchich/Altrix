package com.altrix.orchestrator.adapter.out.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire-format DTO for WebSocket progress events broadcast to Angular clients.
 *
 * <p>Angular contract: {@code {jobId, agentName, status, message?, timestamp}}.
 * {@code message} is suppressed when null via {@link JsonInclude#NON_NULL}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProgressEvent(

        /** Identifies the migration job being tracked. */
        String jobId,

        /** Human-readable name of the agent or pipeline stage. */
        String agentName,

        /** Current status of the step. */
        WorkflowStatus status,

        /** Optional detail — included only when non-null. */
        String message,

        /** ISO-8601 timestamp of when the event was emitted. */
        Instant timestamp

) {
    public static ProgressEvent of(String jobId, String agentName, String rawStatus, String message) {
        WorkflowStatus status;
        try {
            status = WorkflowStatus.valueOf(rawStatus.toUpperCase());
        } catch (IllegalArgumentException e) {
            status = WorkflowStatus.RUNNING;
        }
        return new ProgressEvent(jobId, agentName, status, message, Instant.now());
    }
}
