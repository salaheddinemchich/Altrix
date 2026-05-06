package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

interface AiCallLedgerJpaRepository extends JpaRepository<AiCallLedgerJpaEntity, Long> {

    @Query("""
            SELECT new com.altrix.orchestrator.adapter.out.persistence.UsageSummaryRow(
                e.agentName,
                e.providerName,
                SUM(e.inputTokens),
                SUM(e.outputTokens),
                SUM(e.costUsd),
                COUNT(e)
            )
            FROM AiCallLedgerJpaEntity e
            WHERE e.createdAt >= :from AND e.createdAt < :to
            GROUP BY e.agentName, e.providerName
            ORDER BY SUM(e.costUsd) DESC
            """)
    List<UsageSummaryRow> aggregateUsage(
            @Param("from") Instant from,
            @Param("to") Instant to);
}
