package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import com.altrix.orchestrator.domain.model.user.User;
import com.altrix.orchestrator.domain.model.user.UserAuthProvider;
import com.altrix.orchestrator.domain.model.user.UserRole;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepository {

    private final UserJpaRepository users;
    private final UserAuthProviderJpaRepository providers;

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(String userId) {
        return users.findById(userId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByProviderIdentity(AuthProviderType providerType, String providerId) {
        return providers.findByProviderTypeAndProviderId(providerType, providerId)
                .flatMap(link -> users.findById(link.getUserId()))
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public User upsertWithProvider(AuthProviderType providerType,
                                   String providerId,
                                   String providerLogin,
                                   String email,
                                   String displayName,
                                   String avatarUrl,
                                   String encryptedAccessToken) {
        Instant now = Instant.now();

        UserAuthProviderJpaEntity link = providers.findByProviderTypeAndProviderId(providerType, providerId)
                .orElse(null);

        UserJpaEntity userEntity;
        if (link != null) {
            userEntity = users.findById(link.getUserId()).orElseGet(() -> createUser(email, displayName, avatarUrl, now));
            userEntity.setEmail(email);
            userEntity.setDisplayName(displayName);
            userEntity.setAvatarUrl(avatarUrl);
            userEntity.setUpdatedAt(now);
            userEntity = users.save(userEntity);

            link.setProviderLogin(providerLogin);
            link.setEncryptedAccessToken(encryptedAccessToken);
            link.setUpdatedAt(now);
            providers.save(link);
        } else {
            userEntity = createUser(email, displayName, avatarUrl, now);
            userEntity = users.save(userEntity);

            UserAuthProviderJpaEntity newLink = UserAuthProviderJpaEntity.builder()
                    .userId(userEntity.getId())
                    .providerType(providerType)
                    .providerId(providerId)
                    .providerLogin(providerLogin)
                    .encryptedAccessToken(encryptedAccessToken)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            providers.save(newLink);
        }

        return toDomain(userEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findEncryptedAccessToken(String userId, AuthProviderType providerType) {
        return providers.findEncryptedAccessTokenByUserIdAndProviderType(userId, providerType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserAuthProvider> findProvidersForUser(String userId) {
        return providers.findByUserId(userId).stream().map(this::toDomain).toList();
    }

    private UserJpaEntity createUser(String email, String displayName, String avatarUrl, Instant now) {
        return UserJpaEntity.builder()
                .id(UUID.randomUUID().toString())
                .email(email)
                .displayName(displayName)
                .avatarUrl(avatarUrl)
                .role(UserRole.ROLE_USER)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private User toDomain(UserJpaEntity e) {
        return new User(e.getId(), e.getEmail(), e.getDisplayName(), e.getAvatarUrl(),
                e.getRole(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private UserAuthProvider toDomain(UserAuthProviderJpaEntity p) {
        return new UserAuthProvider(p.getId(), p.getUserId(), p.getProviderType(), p.getProviderId(),
                p.getProviderLogin(), p.getEncryptedAccessToken(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
