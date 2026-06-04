package com.altrix.orchestrator.adapter.out.oauth;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.OAuthTokenLookupPort;
import com.altrix.orchestrator.domain.port.out.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Implements {@link OAuthTokenLookupPort} by reading the user's
 * encrypted token from {@code user_auth_providers} and decrypting it
 * with the existing {@link ApiKeyEncryptionPort}.
 *
 * <p>Mirrors the pattern already used by
 * {@code RepositoryController} so token storage stays a single source
 * of truth (no parallel token caches).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoredOAuthTokenLookupAdapter implements OAuthTokenLookupPort {

    private final UserRepository userRepository;
    private final ApiKeyEncryptionPort encryption;

    @Override
    public Optional<String> findAccessToken(String userId, String providerId) {
        if (userId == null || providerId == null) return Optional.empty();
        AuthProviderType type = mapProvider(providerId);
        if (type == null) return Optional.empty();
        return userRepository.findEncryptedAccessToken(userId, type)
                .map(encryption::decrypt);
    }

    /** Domain provider id ("github", "gitlab", …) → persistence enum. */
    private static AuthProviderType mapProvider(String providerId) {
        try {
            return AuthProviderType.valueOf(providerId.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
