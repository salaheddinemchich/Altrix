package com.altrix.orchestrator.domain.model.report;

import java.util.List;

/**
 * Framework-free page envelope for full-text-search results (#163).
 * Mirrors {@code com.altrix.orchestrator.domain.model.session.SessionPage}
 * so list endpoints share a paginator shape on the wire.
 */
public record ReportSearchPage(
        List<ReportSearchHit> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public ReportSearchPage {
        content = content != null ? List.copyOf(content) : List.of();
    }
}
