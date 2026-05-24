package com.altrix.orchestrator.adapter.in.rest.dto;

import java.util.List;

/**
 * Page envelope for the report-search endpoint (#163) — mirrors the
 * existing {@link SessionPageResponse} shape so the frontend can reuse
 * the same paginator component.
 */
public record ReportSearchPageResponse(
        List<ReportSearchHitResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
