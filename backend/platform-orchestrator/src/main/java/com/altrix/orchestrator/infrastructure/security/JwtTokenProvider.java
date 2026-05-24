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

    public String issueAccessToken(String userId, String login, String email, String role, String provider) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + (long) config.accessTokenExpiryMinutes() * 60_000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())   // jti — enables blacklisting
                .subject(userId)
                .claims(Map.of(
                        "login",    login != null ? login : "",
                        "email",    email != null ? email : "",
                        "role",     role,
                        "provider", provider != null ? provider : "",
                        "type",     "access"
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(rsaKeys.privateKey())     // RS256 auto-detected from RSA PrivateKey
                .compact();
    }

    /** Minimal refresh token — only sub + jti, no PII exposed if the token is intercepted. */
    public String issueRefreshToken(String userId) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + (long) config.refreshTokenExpiryDays() * 86_400_000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
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

    // ── #133 — public shareable links to report versions ─────────────────────

    /** Minimum TTL when issuing a share token: 5 minutes (UX sanity bound). */
    public static final long MIN_SHARE_TTL_MINUTES = 5;
    /** Maximum TTL: 30 days.  Tokens cannot be revoked individually so the
     *  ceiling caps the blast radius of a leaked link. */
    public static final long MAX_SHARE_TTL_MINUTES = 30L * 24 * 60;

    /**
     * Issues a stateless share token for a specific report version (#133).
     *
     * <p>The token is a JWT signed with the same RS256 key as access
     * tokens, so verification re-uses the existing public-key path.  A
     * distinct {@code type} claim ({@code "share"}) prevents a leaked
     * access token from being replayed against the public-share endpoint
     * and vice versa.
     *
     * @param sessionId    session whose report is being shared.
     * @param version      report version number (#162).
     * @param ttlMinutes   desired lifetime; clamped to [MIN_SHARE_TTL_MINUTES,
     *                     MAX_SHARE_TTL_MINUTES].
     * @return the compact JWT — pass to {@link #parseShareToken}.
     */
    public String issueShareToken(String sessionId, int version, long ttlMinutes) {
        long clamped = Math.max(MIN_SHARE_TTL_MINUTES, Math.min(ttlMinutes, MAX_SHARE_TTL_MINUTES));
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + clamped * 60_000L);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject("session-report-share")
                .claims(Map.of(
                        "sessionId", sessionId,
                        "version",   version,
                        "type",      "share"
                ))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(rsaKeys.privateKey())
                .compact();
    }

    /**
     * Parses + validates a share token.  Returns the claims when the
     * signature is valid, the token hasn't expired, and the {@code type}
     * claim is {@code "share"} (so an access token can't sneak in).
     * Throws {@link JwtException} for any other case.
     */
    public Claims parseShareToken(String token) {
        Claims claims = parse(token);
        if (!"share".equals(claims.get("type"))) {
            throw new JwtException("token type is not 'share'");
        }
        return claims;
    }
}
