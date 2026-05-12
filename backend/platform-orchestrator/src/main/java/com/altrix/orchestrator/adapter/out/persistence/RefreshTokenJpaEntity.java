package com.altrix.orchestrator.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens",
       indexes = {
           @Index(name = "idx_refresh_tokens_hash",    columnList = "token_hash"),
           @Index(name = "idx_refresh_tokens_user",    columnList = "user_id"),
           @Index(name = "idx_refresh_tokens_expires", columnList = "expires_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshTokenJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "replaced_by_hash", length = 64)
    private String replacedByHash;
}
