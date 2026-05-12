package com.altrix.orchestrator.domain.model.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Provider-agnostic user aggregate. Identity is the internal UUID — never tied
 * to a specific OAuth provider. Provider links (GitHub, GitLab, …) live in the
 * separate {@link UserAuthProvider} table so a single user can authenticate
 * through multiple providers.
 *
 * <p>Access tokens are never stored on this aggregate — they belong on
 * {@link UserAuthProvider} so the model stays clean of credentials.
 */
public class User {

    private final String id;
    private String email;
    private String displayName;
    private String avatarUrl;
    private UserRole role;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(String id, String email, String displayName, String avatarUrl,
                UserRole role, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.role = role != null ? role : UserRole.ROLE_USER;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User create(String email, String displayName, String avatarUrl) {
        Instant now = Instant.now();
        return new User(UUID.randomUUID().toString(), email, displayName, avatarUrl,
                UserRole.ROLE_USER, now, now);
    }

    public void update(String email, String displayName, String avatarUrl) {
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public String id() { return id; }
    public String email() { return email; }
    public String displayName() { return displayName; }
    public String avatarUrl() { return avatarUrl; }
    public UserRole role() { return role; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
