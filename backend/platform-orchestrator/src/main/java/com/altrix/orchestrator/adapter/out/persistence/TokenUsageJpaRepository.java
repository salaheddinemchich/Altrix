package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface TokenUsageJpaRepository extends JpaRepository<TokenUsageEntity, Long> {

    @Query("""
            SELECT t.providerId    AS providerId,
                   t.tier          AS tier,
                   SUM(t.inputTokens)  AS inputTokens,
                   SUM(t.outputTokens) AS outputTokens,
                   SUM(t.totalTokens)  AS totalTokens,
                   COUNT(t)            AS callCount
            FROM TokenUsageEntity t
            GROUP BY t.providerId, t.tier
            ORDER BY t.providerId, t.tier
            """)
    List<TokenUsageSummaryProjection> findSummary();

    /** Total tokens consumed on or after {@code since} — used for monthly budget enforcement. */
    @Query("SELECT COALESCE(SUM(t.totalTokens), 0) FROM TokenUsageEntity t WHERE t.recordedAt >= :since")
    long sumTotalTokensSince(Instant since);
}
