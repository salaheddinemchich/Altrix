package com.altrix.orchestrator.infrastructure.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the {@code mv_daily_ai_cost} materialized view on a scheduled basis.
 *
 * <p><b>Why CONCURRENTLY?</b> The {@code CONCURRENTLY} option lets Postgres swap
 * in a fresh snapshot without taking an exclusive lock on the view — reads continue
 * uninterrupted during the refresh. This requires the unique index
 * {@code uidx_daily_cost_key} created in V7, which is already in place.
 *
 * <p>Frequency: every hour at minute 0 (off-peak relative to typical API traffic).
 * Configurable via {@code billing.mv-refresh-cron} to allow slower refresh in dev
 * or faster refresh during a high-throughput batch run.
 *
 * <p>Fail-safe: a refresh failure is logged at ERROR level but does not kill the
 * scheduler thread. Stale data is preferable to a scheduler crash cascading into
 * missing future refreshes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingMvRefreshScheduler {

    private final JdbcTemplate jdbcTemplate;

    @Scheduled(cron = "${billing.mv-refresh-cron:0 0 * * * *}")
    public void refreshDailyAiCost() {
        log.debug("Refreshing materialized view mv_daily_ai_cost");
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_daily_ai_cost");
            log.debug("mv_daily_ai_cost refreshed successfully");
        } catch (Exception e) {
            log.error("Failed to refresh mv_daily_ai_cost — billing data may be stale: {}", e.getMessage());
        }
    }
}
