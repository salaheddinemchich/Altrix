package com.altrix.orchestrator.adapter.in.rest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of a full-text-search result over migration reports (#163).
 *
 * @param snippet     {@code ts_headline} output — contains
 *                    {@code <b>term</b>} markers around the matched
 *                    tokens.  Renderable as HTML or stripped for plain
 *                    text consumption.
 * @param rank        Postgres relevance score — higher = better match.
 */
public record ReportSearchHitResponse(
        UUID reportId,
        UUID sessionId,
        int version,
        String projectId,
        Instant generatedAt,
        String snippet,
        float rank
) {}
