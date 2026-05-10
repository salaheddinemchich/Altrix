package com.altrix.orchestrator.domain.model.user;

import java.time.Instant;

/**
 * Domain model for an authenticated user (#81).
 *
 * <p>The GitHub access token is NOT stored here — it lives only in the persistence
 * layer (encrypted) to prevent accidental leakage through serialisation.
 */
public class User {

    private Long id;
    private final String githubId;
    private String githubLogin;
    private String email;
    private String displayName;
    private String avatarUrl;
    private UserRole role;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(Long id, String githubId, String githubLogin, String email,
                String displayName, String avatarUrl, UserRole role,
                Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.githubId = githubId;
        this.githubLogin = githubLogin;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.role = role != null ? role : UserRole.ROLE_USER;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static User create(String githubId, String githubLogin,
                              String email, String displayName, String avatarUrl) {
        Instant now = Instant.now();
        return new User(null, githubId, githubLogin, email, displayName, avatarUrl,
                UserRole.ROLE_USER, now, now);
    }

    public void update(String githubLogin, String email, String displayName, String avatarUrl) {
        this.githubLogin = githubLogin;
        this.email = email;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.updatedAt = Instant.now();
    }

    public Long id() { return id; }
    public String githubId() { return githubId; }
    public String githubLogin() { return githubLogin; }
    public String email() { return email; }
    public String displayName() { return displayName; }
    public String avatarUrl() { return avatarUrl; }
    public UserRole role() { return role; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
