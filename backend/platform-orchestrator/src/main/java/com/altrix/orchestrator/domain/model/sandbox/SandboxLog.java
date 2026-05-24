package com.altrix.orchestrator.domain.model.sandbox;

import java.time.Instant;

/**
 * Framework-free record carrying one sandbox runner's captured output.
 *
 * @param sessionId   session the run belonged to.
 * @param runnerId    e.g. {@code "docker"} or {@code "docker-test"} —
 *                    matches {@link SandboxFinding#runnerId()}.
 * @param content     full stdout+stderr blob.
 * @param exitCode    container exit code; 0 = success, 137 = our timeout
 *                    sentinel.  Null when the runner doesn't have an exit
 *                    code concept (static / migration-quality).
 * @param generatedAt when the runner finished.
 */
public record SandboxLog(
        String sessionId,
        String runnerId,
        String content,
        Integer exitCode,
        Instant generatedAt
) {}
