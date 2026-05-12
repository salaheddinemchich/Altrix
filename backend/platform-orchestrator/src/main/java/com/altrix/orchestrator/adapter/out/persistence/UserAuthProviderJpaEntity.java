package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.user.AuthProviderType;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_auth_providers",
       uniqueConstraints = @UniqueConstraint(name = "uq_uap_provider", columnNames = {"provider_type", "provider_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAuthProviderJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 20)
    private AuthProviderType providerType;

    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    @Column(name = "provider_login", length = 200)
    private String providerLogin;

    @Column(name = "encrypted_access_token", columnDefinition = "TEXT")
    private String encryptedAccessToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
