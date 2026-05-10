package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.auth.RefreshToken;
import com.altrix.orchestrator.domain.port.out.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenPersistenceAdapter implements RefreshTokenRepository {

    private final RefreshTokenJpaRepository repository;

    @Override
    @Transactional
    public RefreshToken save(String tokenHash, String githubId, Instant expiresAt) {
        RefreshTokenJpaEntity entity = RefreshTokenJpaEntity.builder()
                .tokenHash(tokenHash)
                .githubId(githubId)
                .expiresAt(expiresAt)
                .issuedAt(Instant.now())
                .revoked(false)
                .build();
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(this::toDomain);
    }

    @Override
    @Transactional
    public void revokeAndReplace(String tokenHash, String replacedByHash) {
        int updated = repository.revokeAndReplace(tokenHash, replacedByHash);
        if (updated == 0) {
            log.warn("revokeAndReplace: no active token found for hash — already revoked or missing");
        }
    }

    @Override
    @Transactional
    public void revokeAllForUser(String githubId) {
        int count = repository.revokeAllForUser(githubId);
        log.debug("Revoked {} refresh token(s) for githubId={}", count, githubId);
    }

    @Override
    @Transactional
    public void deleteExpiredBefore(Instant cutoff) {
        int count = repository.deleteExpiredBefore(cutoff);
        log.debug("Purged {} expired refresh tokens before {}", count, cutoff);
    }

    private RefreshToken toDomain(RefreshTokenJpaEntity e) {
        return new RefreshToken(e.getId(), e.getTokenHash(), e.getGithubId(),
                e.getExpiresAt(), e.getIssuedAt(), e.isRevoked(), e.getReplacedByHash());
    }
}
