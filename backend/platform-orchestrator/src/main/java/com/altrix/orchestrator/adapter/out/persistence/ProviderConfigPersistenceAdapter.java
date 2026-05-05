package com.altrix.orchestrator.adapter.out.persistence;

import com.altrix.orchestrator.domain.model.provider.ProviderConfig;
import com.altrix.orchestrator.domain.port.out.ProviderConfigRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProviderConfigPersistenceAdapter implements ProviderConfigRepositoryPort {

    private final ProviderConfigJpaRepository jpaRepository;

    @Override
    public Optional<ProviderConfig> findById(String providerId) {
        return jpaRepository.findById(providerId).map(this::toDomain);
    }

    @Override
    public List<ProviderConfig> findAll() {
        return jpaRepository.findAll().stream().map(this::toDomain).toList();
    }

    @Override
    public ProviderConfig save(ProviderConfig config) {
        return toDomain(jpaRepository.save(toEntity(config)));
    }

    // ── mappers ───────────────────────────────────────────────────────────────

    private ProviderConfig toDomain(ProviderConfigEntity e) {
        return ProviderConfig.builder()
                .providerId(e.getProviderId())
                .enabled(e.getEnabled())
                .encryptedApiKey(e.getEncryptedApiKey())
                .baseUrl(e.getBaseUrl())
                .modelAnalysis(e.getModelAnalysis())
                .modelMigration(e.getModelMigration())
                .updatedAt(e.getUpdatedAt())
                .build();
    }

    private ProviderConfigEntity toEntity(ProviderConfig c) {
        return ProviderConfigEntity.builder()
                .providerId(c.getProviderId())
                .enabled(c.getEnabled())
                .encryptedApiKey(c.getEncryptedApiKey())
                .baseUrl(c.getBaseUrl())
                .modelAnalysis(c.getModelAnalysis())
                .modelMigration(c.getModelMigration())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
