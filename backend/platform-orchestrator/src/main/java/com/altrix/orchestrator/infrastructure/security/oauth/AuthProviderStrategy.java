package com.altrix.orchestrator.infrastructure.security.oauth;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Strategy for extracting normalized user identity from a provider-specific
 * {@link OAuth2User}. One implementation per OAuth provider (GitHub, GitLab, …).
 *
 * <p>Implementations live next to the orchestrator's security layer; new
 * providers are added without touching the dispatcher.
 */
public interface AuthProviderStrategy {

    /** The provider this strategy handles. */
    AuthProviderType type();

    /** Spring's OAuth2 client registration id (matches application.yml). */
    String registrationId();

    /** Extracts normalised identity attributes from the provider's user info payload. */
    ExtractedIdentity extract(OAuth2User oauthUser);

    /**
     * Normalised user identity extracted from any provider.
     *
     * @param providerId      stable identifier given by the provider (numeric for GitHub, integer-as-string for GitLab)
     * @param providerLogin   human-readable handle (login, username)
     * @param email           primary email (may be null)
     * @param displayName     full name when available, falling back to login
     * @param avatarUrl       profile picture URL (may be null)
     */
    record ExtractedIdentity(
            String providerId,
            String providerLogin,
            String email,
            String displayName,
            String avatarUrl
    ) {}
}
