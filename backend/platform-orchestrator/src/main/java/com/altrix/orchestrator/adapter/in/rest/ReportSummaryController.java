package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.OrgSummaryResponse;
import com.altrix.orchestrator.domain.model.session.SessionAggregate;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Cross-session aggregate metrics for the dashboard (#131).
 *
 * <p>The ticket originally targeted a separate platform-report microservice
 * (#29); until that ships, the endpoint lives here on the orchestrator
 * since this is where workflow_sessions are written.  The hexagonal split
 * is preserved — the controller calls a domain port, never the JPA
 * repository directly.
 *
 * <p>Caching: deliberately omitted in this first pass.  The aggregates are
 * cheap (one COUNT + one AVG over a small table) and adding Caffeine here
 * would require new dependencies that aren't justified yet.  When session
 * counts grow into the millions the JPQL above is the single place to add
 * {@code @Cacheable("reportSummary")} + a CacheEvict on session save.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ReportSummaryController {

    private final WorkflowSessionRepository sessionRepository;

    @GetMapping("/summary")
    public ResponseEntity<OrgSummaryResponse> summary() {
        SessionAggregate agg = sessionRepository.aggregate();

        Map<String, Long> countsByStatusName = new HashMap<>();
        agg.countsByStatus().forEach((k, v) -> countsByStatusName.put(k.name(), v));

        return ResponseEntity.ok(new OrgSummaryResponse(
                agg.totalSessions(),
                agg.successRate(),
                agg.averageDurationSeconds(),
                countsByStatusName
        ));
    }
}
