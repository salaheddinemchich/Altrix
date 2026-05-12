package com.altrix.orchestrator.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, Long> {

    Optional<RefreshTokenJpaEntity> findByTokenHash(String tokenHash);

    @Modifying
    @Query("""
            UPDATE RefreshTokenJpaEntity r
               SET r.revoked = true, r.replacedByHash = :replacedByHash
             WHERE r.tokenHash = :tokenHash AND r.revoked = false
            """)
    int revokeAndReplace(@Param("tokenHash") String tokenHash,
                         @Param("replacedByHash") String replacedByHash);

    @Modifying
    @Query("UPDATE RefreshTokenJpaEntity r SET r.revoked = true WHERE r.userId = :userId AND r.revoked = false")
    int revokeAllForUser(@Param("userId") String userId);

    @Modifying
    @Query("DELETE FROM RefreshTokenJpaEntity r WHERE r.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
