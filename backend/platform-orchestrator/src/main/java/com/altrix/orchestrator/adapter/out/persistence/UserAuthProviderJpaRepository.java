package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAuthProviderJpaRepository extends JpaRepository<UserAuthProviderJpaEntity, Long> {

    Optional<UserAuthProviderJpaEntity> findByProviderTypeAndProviderId(AuthProviderType providerType, String providerId);

    Optional<UserAuthProviderJpaEntity> findByUserIdAndProviderType(String userId, AuthProviderType providerType);

    List<UserAuthProviderJpaEntity> findByUserId(String userId);

    @Query("""
            SELECT p.encryptedAccessToken
              FROM UserAuthProviderJpaEntity p
             WHERE p.userId = :userId AND p.providerType = :providerType
            """)
    Optional<String> findEncryptedAccessTokenByUserIdAndProviderType(@Param("userId") String userId,
                                                                    @Param("providerType") AuthProviderType providerType);
}
