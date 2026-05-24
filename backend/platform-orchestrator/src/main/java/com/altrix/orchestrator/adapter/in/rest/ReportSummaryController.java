package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.adapter.in.rest.dto.OrgSummaryResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.ReportSearchHitResponse;
import com.altrix.orchestrator.adapter.in.rest.dto.ReportSearchPageResponse;
import com.altrix.orchestrator.domain.model.report.ReportSearchPage;
import com.altrix.orchestrator.domain.model.session.SessionAggregate;
import com.altrix.orchestrator.domain.port.out.MigrationReportRepository;
import com.altrix.orchestrator.domain.port.out.WorkflowSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final MigrationReportRepository migrationReportRepository;

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

    /**
     * GET /api/v1/reports/search?q=...&page=0&size=20 — full-text search
     * across persisted migration-report content (#163).
     *
     * <p>The query is passed through Postgres'
     * {@code plainto_tsquery('english', ...)} which safely tokenises user
     * input (terms AND'd together, special characters escaped) — no
     * tsquery-injection surface.  Page size clamped to [1, 100] in the
     * adapter.  An empty / blank query returns an empty page rather than
     * "everything", which is rarely what callers want.
     */
    @GetMapping("/search")
    public ResponseEntity<ReportSearchPageResponse> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        ReportSearchPage result = migrationReportRepository.search(query, page, size);
        return ResponseEntity.ok(new ReportSearchPageResponse(
                result.content().stream()
                        .map(h -> new ReportSearchHitResponse(
                                h.reportId(), h.sessionId(), h.version(),
                                h.projectId(), h.generatedAt(),
                                h.snippet(), h.rank()))
                        .toList(),
                result.page(),
                result.size(),
                result.totalElements(),
                result.totalPages()
        ));
    }
}
