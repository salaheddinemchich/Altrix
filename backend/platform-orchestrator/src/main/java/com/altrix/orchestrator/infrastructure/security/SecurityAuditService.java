package com.altrix.orchestrator.infrastructure.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Structured security audit logger.
 *
 * <p>All security events are written via SLF4J using a consistent key=value format
 * compatible with log aggregation systems (ELK, Datadog, Splunk).
 *
 * <p>Audit events NEVER log passwords, raw tokens, or private keys. JTI (UUID) is
 * safe to log — it is not the token itself.
 */
@Slf4j
@Component
public class SecurityAuditService {

    public void loginSuccess(String login, String ip) {
        log.info("SECURITY_EVENT=LOGIN_SUCCESS user={} ip={}", login, ip);
    }

    public void loginFailure(String ip, String reason) {
        log.warn("SECURITY_EVENT=LOGIN_FAILURE ip={} reason={}", ip, reason);
    }

    public void logout(String userId, String jti, String ip) {
        log.info("SECURITY_EVENT=LOGOUT userId={} jti={} ip={}", userId, jti, ip);
    }

    public void tokenRefreshed(String userId, String ip) {
        log.info("SECURITY_EVENT=TOKEN_REFRESHED userId={} ip={}", userId, ip);
    }

    public void tokenReplayDetected(String userId, String ip) {
        log.warn("SECURITY_EVENT=TOKEN_REPLAY_DETECTED userId={} ip={} action=REVOKE_ALL_TOKENS",
                userId, ip);
    }

    public void bruteForceBlocked(String ip, int attempts) {
        log.warn("SECURITY_EVENT=BRUTE_FORCE_BLOCKED ip={} failedAttempts={}", ip, attempts);
    }

    public void rateLimitExceeded(String ip, String path) {
        log.warn("SECURITY_EVENT=RATE_LIMIT_EXCEEDED ip={} path={}", ip, path);
    }

    public void unauthorizedAccess(String path, String ip) {
        log.warn("SECURITY_EVENT=UNAUTHORIZED_ACCESS path={} ip={}", path, ip);
    }

    public void adminAction(String adminUserId, String action, String targetUserId) {
        log.info("SECURITY_EVENT=ADMIN_ACTION admin={} action={} target={}",
                adminUserId, action, targetUserId);
    }
}
