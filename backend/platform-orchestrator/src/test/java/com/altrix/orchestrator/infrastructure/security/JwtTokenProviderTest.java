package com.altrix.orchestrator.infrastructure.security;

import com.altrix.orchestrator.infrastructure.config.JwtConfig;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        // Blank keys → ephemeral RSA pair generated automatically
        JwtConfig config = new JwtConfig("", "", 15, 7);
        RsaKeyProvider rsaKeys = new RsaKeyProvider(config);
        provider = new JwtTokenProvider(config, rsaKeys);
    }

    // ── Access token ──────────────────────────────────────────────────────────

    @Test
    void access_token_is_valid_and_parses_claims() {
        String token = provider.issueAccessToken("gh-42", "alice", "alice@example.com", "ROLE_USER", "GITHUB");

        assertThat(provider.isValid(token)).isTrue();

        Claims claims = provider.parse(token);
        assertThat(claims.getSubject()).isEqualTo("gh-42");
        assertThat(claims.get("login",  String.class)).isEqualTo("alice");
        assertThat(claims.get("email",  String.class)).isEqualTo("alice@example.com");
        assertThat(claims.get("role",   String.class)).isEqualTo("ROLE_USER");
        assertThat(claims.get("type",   String.class)).isEqualTo("access");
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    void access_token_has_unique_jti_per_issuance() {
        String t1 = provider.issueAccessToken("gh-42", "alice", "alice@example.com", "ROLE_USER", "GITHUB");
        String t2 = provider.issueAccessToken("gh-42", "alice", "alice@example.com", "ROLE_USER", "GITHUB");

        assertThat(provider.parse(t1).getId())
                .isNotEqualTo(provider.parse(t2).getId());
    }

    // ── Refresh token ─────────────────────────────────────────────────────────

    @Test
    void refresh_token_is_valid_and_carries_type_claim() {
        String token = provider.issueRefreshToken("gh-42");

        assertThat(provider.isValid(token)).isTrue();

        Claims claims = provider.parse(token);
        assertThat(claims.getSubject()).isEqualTo("gh-42");
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        assertThat(claims.get("role", String.class)).isNull();
        assertThat(claims.get("email", String.class)).isNull();
    }

    // ── Tamper detection ──────────────────────────────────────────────────────

    @Test
    void tampered_token_is_invalid() {
        String token = provider.issueAccessToken("gh-42", "alice", "alice@example.com", "ROLE_USER", "GITHUB");
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(provider.isValid(tampered)).isFalse();
    }

    @Test
    void garbage_string_is_invalid() {
        assertThat(provider.isValid("not.a.jwt")).isFalse();
        assertThat(provider.isValid("")).isFalse();
    }

    // ── TTL helper ────────────────────────────────────────────────────────────

    @Test
    void remaining_ttl_is_positive_for_fresh_token() {
        String token = provider.issueAccessToken("gh-42", "alice", "a@b.com", "ROLE_USER", "GITHUB");
        Claims claims = provider.parse(token);

        assertThat(provider.remainingTtlMs(claims))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(15L * 60_000L);
    }

    // ── RS256 — different keypair cannot verify ───────────────────────────────

    @Test
    void token_signed_by_different_key_is_rejected() {
        JwtConfig otherConfig = new JwtConfig("", "", 15, 7);
        RsaKeyProvider otherKeys = new RsaKeyProvider(otherConfig);
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherConfig, otherKeys);

        String foreignToken = otherProvider.issueAccessToken("gh-1", "bob", "b@b.com", "ROLE_USER", "GITHUB");

        // Our provider should reject a token signed by a different key
        assertThat(provider.isValid(foreignToken)).isFalse();
    }

    // ── Share token (#133) ────────────────────────────────────────────────────

    @Test
    void share_token_carries_sessionId_version_and_typeShare() {
        String sessionId = java.util.UUID.randomUUID().toString();
        String token = provider.issueShareToken(sessionId, 3, 60);

        Claims claims = provider.parseShareToken(token);
        assertThat(claims.get("sessionId", String.class)).isEqualTo(sessionId);
        assertThat(claims.get("version", Integer.class)).isEqualTo(3);
        assertThat(claims.get("type", String.class)).isEqualTo("share");
        assertThat(claims.getSubject()).isEqualTo("session-report-share");
    }

    @Test
    void parseShareToken_rejects_access_token() {
        String access = provider.issueAccessToken("gh-1", "alice", "a@a.com", "ROLE_USER", "GITHUB");
        // Signature is valid (same key) but type != share — must reject.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.parseShareToken(access))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void share_token_ttl_clamped_to_min_when_too_low() {
        String sessionId = java.util.UUID.randomUUID().toString();
        String token = provider.issueShareToken(sessionId, 1, /*requested*/ 1);
        Claims claims = provider.parseShareToken(token);
        // 1 minute requested, clamped to MIN_SHARE_TTL_MINUTES (5 min).
        long remaining = provider.remainingTtlMs(claims);
        assertThat(remaining).isBetween(4L * 60_000L, 5L * 60_000L + 1000L);
    }

    @Test
    void share_token_ttl_clamped_to_max_when_too_high() {
        String sessionId = java.util.UUID.randomUUID().toString();
        // Request 100 days — should clamp to 30 days.
        String token = provider.issueShareToken(sessionId, 1, 100L * 24 * 60);
        Claims claims = provider.parseShareToken(token);
        long remaining = provider.remainingTtlMs(claims);
        long maxMs = JwtTokenProvider.MAX_SHARE_TTL_MINUTES * 60_000L;
        // Allow a small tolerance below the ceiling for the time between
        // token creation and the assertion below.
        assertThat(remaining).isBetween(maxMs - 60_000L, maxMs + 1000L);
    }
}
