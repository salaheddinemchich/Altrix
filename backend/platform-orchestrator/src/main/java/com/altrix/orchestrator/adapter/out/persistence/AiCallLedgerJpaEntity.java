package com.altrix.orchestrator.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "ai_call_ledger")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiCallLedgerJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", length = 36)
    private String jobId;

    @Column(name = "agent_name", length = 100)
    private String agentName;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(nullable = false, length = 20)
    private String tier;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 8)
    private BigDecimal costUsd;

    @Column(name = "cache_hit", nullable = false)
    private boolean cacheHit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
