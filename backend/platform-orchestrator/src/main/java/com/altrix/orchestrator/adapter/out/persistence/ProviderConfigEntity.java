package com.altrix.orchestrator.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "provider_configs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfigEntity {

    @Id
    @Column(name = "provider_id", length = 50, nullable = false)
    private String providerId;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "encrypted_api_key", columnDefinition = "TEXT")
    private String encryptedApiKey;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "model_analysis", length = 200)
    private String modelAnalysis;

    @Column(name = "model_migration", length = 200)
    private String modelMigration;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
