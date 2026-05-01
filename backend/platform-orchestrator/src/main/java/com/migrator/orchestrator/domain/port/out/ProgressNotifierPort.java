package com.migrator.orchestrator.domain.port.out;

/**
 * Secondary port — streams progress events to the client.
 * The domain never imports WebSocket or Spring Messaging.
 */
public interface ProgressNotifierPort {

    /**
     * Broadcasts a progress update to all clients subscribed to this job.
     *
     * @param jobId     the job being tracked
     * @param agentName name of the agent that just ran
     * @param status    "RUNNING" | "DONE" | "FAILED"
     * @param message   optional detail message
     */
    void notify(String jobId, String agentName, String status, String message);
}
