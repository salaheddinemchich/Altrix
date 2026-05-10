package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.user.User;

import java.util.Optional;

/**
 * Secondary port — persists and queries authenticated users (#81).
 */
public interface UserRepository {

    User save(User user);

    Optional<User> findByGithubId(String githubId);

    /**
     * Upsert by GitHub ID — creates if absent, updates profile fields if present.
     */
    User upsert(User user, String encryptedAccessToken);
}
