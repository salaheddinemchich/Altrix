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
}
