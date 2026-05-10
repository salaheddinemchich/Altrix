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
 *
 * <p>Each event includes a correlation ID where available so a full login → token use
 * → logout sequence can be traced across log lines.
 *
 * <p>Security events to capture (OWASP ASVS 7.2):
 * <ul>
 *   <li>Login success / failure
 *   <li>Logout
 *   <li>Token refresh
 *   <li>Suspicious activity (replay attack, brute-force)
 *   <li>Role changes (admin actions)
 *   <li>Access to sensitive endpoints
 * </ul>
 */
@Slf4j
@Component
public class SecurityAuditService {

    public void loginSuccess(String githubLogin, String ip) {
        log.info("SECURITY_EVENT=LOGIN_SUCCESS user={} ip={}", githubLogin, ip);
    }

    public void loginFailure(String ip, String reason) {
        log.warn("SECURITY_EVENT=LOGIN_FAILURE ip={} reason={}", ip, reason);
    }

    public void logout(String githubId, String jti, String ip) {
        log.info("SECURITY_EVENT=LOGOUT githubId={} jti={} ip={}", githubId, jti, ip);
    }

    public void tokenRefreshed(String githubId, String ip) {
        log.info("SECURITY_EVENT=TOKEN_REFRESHED githubId={} ip={}", githubId, ip);
    }

    public void tokenReplayDetected(String githubId, String ip) {
        log.warn("SECURITY_EVENT=TOKEN_REPLAY_DETECTED githubId={} ip={} action=REVOKE_ALL_TOKENS",
                githubId, ip);
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

    public void adminAction(String adminGithubId, String action, String targetGithubId) {
        log.info("SECURITY_EVENT=ADMIN_ACTION admin={} action={} target={}",
                adminGithubId, action, targetGithubId);
    }
}
