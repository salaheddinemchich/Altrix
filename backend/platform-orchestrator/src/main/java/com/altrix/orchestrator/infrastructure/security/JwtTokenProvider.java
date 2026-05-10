package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.infrastructure.config.JwtConfig;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates RS256-signed JWT tokens.
 *
 * <p><b>Why RS256 (not HS256)?</b> Asymmetric signing: resource servers verify tokens
 * with the public key only — they never hold the private key and cannot forge tokens.
 * A compromised downstream service cannot issue new access tokens.
 *
 * <p><b>Token types:</b>
 * <ul>
 *   <li><b>Access token</b>  — 15 min, carries sub/login/email/role/jti.
 *   <li><b>Refresh token</b> — 7 days, carries only sub/jti to minimise blast radius.
 * </ul>
 *
 * <p><b>jti claim:</b> every token gets a UUID. Revoked JTIs are stored in Redis
 * with a TTL equal to the token's remaining lifetime, enabling O(1) logout and
 * replay-attack detection without a DB round-trip on every request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtConfig config;
    private final RsaKeyProvider rsaKeys;

    // ── Token issuance ────────────────────────────────────────────────────────

    public String issueAccessToken(String githubId, String login, String email, String role) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + (long) config.accessTokenExpiryMinutes() * 60_000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — enables blacklisting
                .subject(githubId)
                .claims(Map.of(
                        "login", login,
                        "email", email != null ? email : "",
                        "role",  role,
                        "type",  "access"
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(rsaKeys.privateKey())     // RS256 auto-detected from RSA PrivateKey
                .compact();
    }

    /** Minimal refresh token — only sub + jti, no PII exposed if the token is intercepted. */
    public String issueRefreshToken(String githubId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + (long) config.refreshTokenExpiryDays() * 86_400_000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(githubId)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(rsaKeys.privateKey())
                .compact();
    }

    // ── Token validation ──────────────────────────────────────────────────────

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(rsaKeys.publicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /** Milliseconds remaining until this token expires (never negative). */
    public long remainingTtlMs(Claims claims) {
        return Math.max(0, claims.getExpiration().getTime() - System.currentTimeMillis());
    }

    public int accessTokenExpiryMinutes() {
        return config.accessTokenExpiryMinutes();
    }
}
