package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.domain.port.in.GetResilienceMetricsUseCase;
import com.altrix.orchestrator.domain.port.in.ProviderResilienceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter for the AI resilience dashboard (#147).
 *
 * <ul>
 *   <li>GET /api/ai/resilience — live circuit breaker states + bulkhead permits per tier</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/resilience")
@RequiredArgsConstructor
public class ResilienceController {

    private final GetResilienceMetricsUseCase getResilienceMetrics;

    @GetMapping
    public ResponseEntity<ProviderResilienceStatus> getStatus() {
        return ResponseEntity.ok(getResilienceMetrics.getResilienceStatus());
    }
}
