package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.sandbox.SandboxLog;

import java.time.Instant;

/**
 * REST shape for one persisted sandbox-runner log (#105).  Drives the
 * log viewer expansion on the Validate step of the JobDetail timeline.
 */
public record SandboxLogResponse(
        String runnerId,
        String content,
        Integer exitCode,
        Instant generatedAt
) {
    public static SandboxLogResponse from(SandboxLog log) {
        return new SandboxLogResponse(
                log.runnerId(),
                log.content(),
                log.exitCode(),
                log.generatedAt()
        );
    }
}
