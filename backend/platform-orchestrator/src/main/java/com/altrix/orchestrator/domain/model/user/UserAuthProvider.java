package com.altrix.orchestrator.domain.model.user;

import java.time.Instant;

/**
 * Value object linking an internal {@link User} to a single OAuth2 identity provider.
 *
 * <p>A user may have multiple providers (e.g. GitHub + GitLab) — each row carries
 * the encrypted access token used for that provider's API calls. The token is
 * encrypted at rest via {@link com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort}.
 *
 * <p>{@code providerLogin} is the human-readable handle ("octocat") used for display,
 * {@code providerId} is the stable numeric/string identifier from the provider.
 */
public record UserAuthProvider(
        Long id,
        String userId,
        AuthProviderType providerType,
        String providerId,
        String providerLogin,
        String encryptedAccessToken,
        Instant createdAt,
        Instant updatedAt
) {}
