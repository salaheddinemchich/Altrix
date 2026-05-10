package com.altrix.orchestrator.infrastructure.scheduler;

import com.altrix.orchestrator.domain.port.out.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Purges expired refresh tokens from the DB on a daily schedule.
 *
 * <p>Expired tokens are already unusable (checked in {@link com.altrix.orchestrator.domain.service.TokenService}),
 * but they would accumulate indefinitely without this cleanup, eventually filling the table.
 * Running nightly at 03:00 UTC minimises load during business hours.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenMaintenanceScheduler {

    private final RefreshTokenRepository refreshTokenRepository;

    @Scheduled(cron = "${security.refresh-token.cleanup-cron:0 0 3 * * *}")
    public void purgeExpiredTokens() {
        log.debug("Running refresh token cleanup");
        refreshTokenRepository.deleteExpiredBefore(Instant.now());
    }
}
