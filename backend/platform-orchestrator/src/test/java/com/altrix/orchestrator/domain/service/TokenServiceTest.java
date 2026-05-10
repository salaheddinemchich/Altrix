package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.model.auth.RefreshToken;
import com.altrix.orchestrator.domain.port.out.RefreshTokenRepository;
import com.altrix.orchestrator.domain.port.out.TokenBlacklistPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    RefreshTokenRepository refreshTokenRepo;
    @Mock
    TokenBlacklistPort tokenBlacklist;

    TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(refreshTokenRepo, tokenBlacklist);
    }

    // ── storeRefreshToken ─────────────────────────────────────────────────────

    @Test
    void store_delegates_to_repository() {
        Instant exp = Instant.now().plusSeconds(3600);
        tokenService.storeRefreshToken("hash-abc", "gh-1", exp);
        verify(refreshTokenRepo).save("hash-abc", "gh-1", exp);
    }

    // ── rotateRefreshToken — happy path ───────────────────────────────────────

    @Test
    void rotate_returns_github_id_on_valid_token() {
        RefreshToken valid = validToken("gh-1", "old-hash");
        when(refreshTokenRepo.findByTokenHash("old-hash")).thenReturn(Optional.of(valid));

        Optional<String> result = tokenService.rotateRefreshToken("old-hash", "new-hash");

        assertThat(result).contains("gh-1");
        verify(refreshTokenRepo).revokeAndReplace("old-hash", "new-hash");
    }

    // ── rotateRefreshToken — not found ────────────────────────────────────────

    @Test
    void rotate_returns_empty_when_token_not_found() {
        when(refreshTokenRepo.findByTokenHash("unknown")).thenReturn(Optional.empty());

        assertThat(tokenService.rotateRefreshToken("unknown", "new")).isEmpty();
        verify(refreshTokenRepo, never()).revokeAndReplace(any(), any());
    }

    // ── rotateRefreshToken — replayed revoked token ───────────────────────────

    @Test
    void rotate_revokes_entire_family_on_revoked_token_replay() {
        RefreshToken revoked = revokedToken("gh-1", "revoked-hash");
        when(refreshTokenRepo.findByTokenHash("revoked-hash")).thenReturn(Optional.of(revoked));

        Optional<String> result = tokenService.rotateRefreshToken("revoked-hash", "new-hash");

        assertThat(result).isEmpty();
        verify(refreshTokenRepo).revokeAllForUser("gh-1");
        verify(refreshTokenRepo, never()).revokeAndReplace(any(), any());
    }

    // ── rotateRefreshToken — expired ──────────────────────────────────────────

    @Test
    void rotate_returns_empty_for_expired_token() {
        RefreshToken expired = expiredToken("gh-1", "expired-hash");
        when(refreshTokenRepo.findByTokenHash("expired-hash")).thenReturn(Optional.of(expired));

        assertThat(tokenService.rotateRefreshToken("expired-hash", "new-hash")).isEmpty();
        verify(refreshTokenRepo, never()).revokeAndReplace(any(), any());
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_blacklists_jti_and_revokes_all_refresh_tokens() {
        tokenService.logout("jti-xyz", 300L, "gh-1");

        verify(tokenBlacklist).blacklist("jti-xyz", 300L);
        verify(refreshTokenRepo).revokeAllForUser("gh-1");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static RefreshToken validToken(String githubId, String hash) {
        return new RefreshToken(1L, hash, githubId,
                Instant.now().plusSeconds(86_400), Instant.now(), false, null);
    }

    private static RefreshToken revokedToken(String githubId, String hash) {
        return new RefreshToken(2L, hash, githubId,
                Instant.now().plusSeconds(86_400), Instant.now(), true, null);
    }

    private static RefreshToken expiredToken(String githubId, String hash) {
        return new RefreshToken(3L, hash, githubId,
                Instant.now().minusSeconds(1), Instant.now().minusSeconds(3600), false, null);
    }
}
