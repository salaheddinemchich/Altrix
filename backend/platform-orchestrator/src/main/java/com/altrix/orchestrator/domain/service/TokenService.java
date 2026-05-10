package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.model.auth.RefreshToken;
import com.altrix.orchestrator.domain.port.out.RefreshTokenRepository;
import com.altrix.orchestrator.domain.port.out.TokenBlacklistPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Optional;

/**
 * Domain service for refresh-token rotation and logout.
 *
 * <p>No Spring or framework imports — only domain ports. All token hashing is done
 * by callers in the infrastructure layer (SHA-256 via Java Security).
 *
 * <p><b>Rotation strategy:</b>
 * Each refresh produces a new token; the old one is immediately revoked.
 * If the same (revoked) token is presented again, the entire token family is
 * revoked — this detects refresh token theft / replay attacks.
 */
@Slf4j
@RequiredArgsConstructor
public class TokenService {

    private final RefreshTokenRepository refreshTokenRepo;
    private final TokenBlacklistPort tokenBlacklist;

    /**
     * Persists a new refresh token entry after successful login.
     */
    public void storeRefreshToken(String tokenHash, String githubId, Instant expiresAt) {
        refreshTokenRepo.save(tokenHash, githubId, expiresAt);
    }

    /**
     * Validates and rotates a refresh token.
     *
     * @param oldTokenHash SHA-256 hex of the token the client presented
     * @param newTokenHash SHA-256 hex of the newly issued replacement
     * @return the githubId of the token owner, or empty if the token is invalid
     */
    public Optional<String> rotateRefreshToken(String oldTokenHash, String newTokenHash) {
        Optional<RefreshToken> found = refreshTokenRepo.findByTokenHash(oldTokenHash);

        if (found.isEmpty()) {
            log.warn("Refresh token not found — possible replay attack or invalid token");
            return Optional.empty();
        }

        RefreshToken token = found.get();

        if (token.revoked()) {
            // A previously revoked token is being replayed — revoke the entire family
            log.warn("Revoked refresh token replayed for githubId={} — revoking all tokens (theft suspected)",
                    token.githubId());
            refreshTokenRepo.revokeAllForUser(token.githubId());
            return Optional.empty();
        }

        if (token.isExpired()) {
            log.debug("Expired refresh token presented for githubId={}", token.githubId());
            return Optional.empty();
        }

        refreshTokenRepo.revokeAndReplace(oldTokenHash, newTokenHash);
        return Optional.of(token.githubId());
    }

    /**
     * Logs out a user: blacklists the current access token and revokes all refresh tokens.
     *
     * @param accessTokenJti  jti of the current access token
     * @param accessTtlSecs   remaining lifetime of the access token (for Redis TTL)
     * @param githubId        user to revoke all refresh tokens for
     */
    public void logout(String accessTokenJti, long accessTtlSecs, String githubId) {
        tokenBlacklist.blacklist(accessTokenJti, accessTtlSecs);
        refreshTokenRepo.revokeAllForUser(githubId);
        log.info("Logged out githubId={} — access token blacklisted, refresh tokens revoked", githubId);
    }
}
