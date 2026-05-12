package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import com.altrix.orchestrator.domain.model.user.User;
import com.altrix.orchestrator.domain.model.user.UserAuthProvider;

import java.util.List;
import java.util.Optional;

/**
 * Provider-agnostic user persistence. {@link User} identity is the internal
 * UUID; provider-specific identities live in {@link UserAuthProvider}.
 */
public interface UserRepository {

    Optional<User> findById(String userId);

    Optional<User> findByProviderIdentity(AuthProviderType providerType, String providerId);

    /**
     * Upsert by external provider identity:
     * <ul>
     *   <li>If a {@link UserAuthProvider} row matches (providerType, providerId),
     *       update its login and encrypted token in place.</li>
     *   <li>Otherwise, create a new {@link User} and link this provider to it.</li>
     * </ul>
     * Returns the resolved user.
     */
    User upsertWithProvider(AuthProviderType providerType,
                            String providerId,
                            String providerLogin,
                            String email,
                            String displayName,
                            String avatarUrl,
                            String encryptedAccessToken);

    /**
     * Returns the encrypted access token a given user holds for a given provider.
     * Empty if the user has not linked that provider.
     */
    Optional<String> findEncryptedAccessToken(String userId, AuthProviderType providerType);

    List<UserAuthProvider> findProvidersForUser(String userId);
}
