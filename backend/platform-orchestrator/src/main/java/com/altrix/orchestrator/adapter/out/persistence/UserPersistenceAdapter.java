package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.user.User;
import com.altrix.orchestrator.domain.model.user.UserRole;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepository {

    private final UserJpaRepository repository;

    @Override
    @Transactional
    public User save(User user) {
        return toDomain(repository.save(toEntity(user, null)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findByGithubId(String githubId) {
        return repository.findByGithubId(githubId).map(this::toDomain);
    }

    @Override
    @Transactional
    public User upsert(User user, String encryptedAccessToken) {
        UserJpaEntity entity = repository.findByGithubId(user.githubId())
                .orElseGet(() -> UserJpaEntity.builder()
                        .githubId(user.githubId())
                        .role(UserRole.ROLE_USER)
                        .createdAt(Instant.now())
                        .build());

        entity.setGithubLogin(user.githubLogin());
        entity.setEmail(user.email());
        entity.setDisplayName(user.displayName());
        entity.setAvatarUrl(user.avatarUrl());
        entity.setEncryptedAccessToken(encryptedAccessToken);
        entity.setUpdatedAt(Instant.now());

        return toDomain(repository.save(entity));
    }

    private UserJpaEntity toEntity(User u, String encryptedToken) {
        return UserJpaEntity.builder()
                .id(u.id())
                .githubId(u.githubId())
                .githubLogin(u.githubLogin())
                .email(u.email())
                .displayName(u.displayName())
                .avatarUrl(u.avatarUrl())
                .encryptedAccessToken(encryptedToken)
                .role(u.role() != null ? u.role() : UserRole.ROLE_USER)
                .createdAt(u.createdAt())
                .updatedAt(u.updatedAt())
                .build();
    }

    private User toDomain(UserJpaEntity e) {
        return new User(e.getId(), e.getGithubId(), e.getGithubLogin(),
                e.getEmail(), e.getDisplayName(), e.getAvatarUrl(),
                e.getRole(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
