package com.altrix.project.infrastructure.scheduler;

import com.altrix.project.domain.port.out.WebhookDeliveryRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Nightly purge of webhook audit rows beyond the retention window (#91).
 *
 * <p>Runs at 02:00 server-time by default; configurable via
 * {@code webhook.purge.cron} and {@code webhook.purge.retention-days}.
 *
 * <p>{@code @Scheduled} relies on {@code @EnableScheduling} which is added on
 * {@link com.altrix.project.ProjectApplication}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryPurgeScheduler {

    private final WebhookDeliveryRepositoryPort deliveryRepository;

    @Value("${webhook.purge.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${webhook.purge.cron:0 0 2 * * ?}")
    public void purgeOldDeliveries() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int removed = deliveryRepository.deleteAllReceivedBefore(cutoff);
        if (removed > 0) {
            log.info("Purged {} webhook delivery rows older than {} days", removed, retentionDays);
        }
    }
}
