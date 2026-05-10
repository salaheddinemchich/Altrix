package com.altrix.orchestrator.domain.model.session;

import java.util.List;

/**
 * Framework-free pagination envelope returned by {@link com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository#findAll}.
 */
public record SessionPage(
        List<WorkflowSession> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
