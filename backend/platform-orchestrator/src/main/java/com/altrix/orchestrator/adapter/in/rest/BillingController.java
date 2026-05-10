package com.altrix.orchestrator.adapter.in.rest;

import com.altrix.orchestrator.domain.model.AiCallUsageSummary;
import com.altrix.orchestrator.domain.port.out.AiCallLedgerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Billing usage endpoint (#53).
 *
 * <p>{@code GET /api/v1/billing/usage?from=...&to=...} returns total token counts
 * and estimated cost grouped by agent and provider for the requested window.
 * Defaults to the current calendar month (UTC) when no range is supplied.
 */
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class BillingController {

    private final AiCallLedgerPort aiCallLedgerPort;

    @GetMapping("/usage")
    public List<AiCallUsageSummary> getUsage(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Instant resolvedFrom = from != null ? from : startOfCurrentMonth();
        Instant resolvedTo = to != null ? to : Instant.now();
        return aiCallLedgerPort.queryUsage(resolvedFrom, resolvedTo);
    }

    private static Instant startOfCurrentMonth() {
        return Instant.now()
                .atZone(java.time.ZoneOffset.UTC)
                .withDayOfMonth(1)
                .truncatedTo(ChronoUnit.DAYS)
                .toInstant();
    }
}
