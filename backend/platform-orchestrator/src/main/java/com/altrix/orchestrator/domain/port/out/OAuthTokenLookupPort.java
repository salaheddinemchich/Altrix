package com.altrix.orchestrator.domain.port.out;

import java.util.Optional;

/**
 * Resolves the OAuth access token to use when calling a repository
 * provider on behalf of a user.  Kept as a port so the domain services
 * (which know the user id and provider id but not the token storage)
 * can ask for a token without caring where it lives.
 *
 * <p>The returned token MUST never be logged or surfaced in responses.
 */
public interface OAuthTokenLookupPort {

    /**
     * @param userId     internal user id (the actor).
     * @param providerId provider this token authorises against, e.g. {@code "github"}.
     * @return the access token if one is stored and not expired; empty otherwise.
     */
    Optional<String> findAccessToken(String userId, String providerId);
}
