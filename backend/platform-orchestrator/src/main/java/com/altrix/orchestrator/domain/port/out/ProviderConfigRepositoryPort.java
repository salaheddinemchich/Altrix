package com.altrix.orchestrator.domain.port.out;

import com.altrix.orchestrator.domain.model.provider.ProviderConfig;

import java.util.List;
import java.util.Optional;

public interface ProviderConfigRepositoryPort {
    Optional<ProviderConfig> findById(String providerId);
    List<ProviderConfig> findAll();
    ProviderConfig save(ProviderConfig config);
}
