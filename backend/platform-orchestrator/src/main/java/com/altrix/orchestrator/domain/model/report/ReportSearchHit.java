package com.altrix.orchestrator.domain.model.report;

import java.time.Instant;
import java.util.UUID;

/**
 * One framework-free search hit (#163).  Carries the identifying triple
 * plus a highlighted snippet rendered server-side and a relevance score
 * the UI can use for ranking visual cues.
 *
 * @param snippet {@code ts_headline} output — surrounds matched tokens
 *                with {@code <b>...</b>} so the frontend can render it
 *                as HTML or strip the tags for plain text.
 * @param rank    Postgres {@code ts_rank} — higher = better match.
 */
public record ReportSearchHit(
        UUID reportId,
        UUID sessionId,
        int version,
        String projectId,
        Instant generatedAt,
        String snippet,
        float rank
) {}
