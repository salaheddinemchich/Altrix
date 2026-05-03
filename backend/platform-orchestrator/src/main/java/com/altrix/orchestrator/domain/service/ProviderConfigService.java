package com.altrix.orchestrator.domain.service;

import com.altrix.orchestrator.domain.model.provider.ProviderConfig;
import com.altrix.orchestrator.domain.port.in.GetProviderConfigsUseCase;
import com.altrix.orchestrator.domain.port.in.ProviderConfigView;
import com.altrix.orchestrator.domain.port.in.SaveProviderConfigCommand;
import com.altrix.orchestrator.domain.port.in.UpdateProviderConfigUseCase;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.ProviderConfigRepository;
import com.altrix.orchestrator.domain.port.out.ProviderRefreshPort;
import com.altrix.orchestrator.infra.ai.provider.factory.ProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class ProviderConfigService implements UpdateProviderConfigUseCase, GetProviderConfigsUseCase {

    private final ProviderConfigRepository configRepository;
    private final ApiKeyEncryptionPort     encryption;
    private final ProviderRefreshPort      providerRefresh;
    private final List<ProviderFactory>    factories;   // all known provider factories

    @Override
    public void updateProviderConfig(SaveProviderConfigCommand command) {
        ProviderConfig existing = configRepository
                .findById(command.providerId())
                .orElseGet(() -> ProviderConfig.createEmpty(command.providerId()));

        ProviderConfig updated = applyChanges(existing, command);
        configRepository.save(updated);

        log.info("Provider config updated: id={}", command.providerId());
        providerRefresh.refreshProviders();
    }

    @Override
    public List<ProviderConfigView> getProviderConfigs() {
        Map<String, ProviderConfig> overrides = configRepository.findAll().stream()
                .collect(Collectors.toMap(ProviderConfig::getProviderId, Function.identity()));

        return factories.stream()
                .map(factory -> toView(factory, overrides.get(factory.providerId())))
                .toList();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private ProviderConfig applyChanges(ProviderConfig existing, SaveProviderConfigCommand cmd) {
        ProviderConfig.ProviderConfigBuilder builder = existing.toBuilder()
                .updatedAt(Instant.now());

        if (cmd.enabled() != null) {
            builder.enabled(cmd.enabled());
        }
        if (cmd.plainApiKey() != null) {
            // empty string means "clear the override"
            builder.encryptedApiKey(
                    cmd.plainApiKey().isBlank() ? null : encryption.encrypt(cmd.plainApiKey()));
        }
        if (cmd.baseUrl() != null) {
            builder.baseUrl(cmd.baseUrl().isBlank() ? null : cmd.baseUrl());
        }
        if (cmd.modelAnalysis() != null) {
            builder.modelAnalysis(cmd.modelAnalysis().isBlank() ? null : cmd.modelAnalysis());
        }
        if (cmd.modelMigration() != null) {
            builder.modelMigration(cmd.modelMigration().isBlank() ? null : cmd.modelMigration());
        }

        return builder.build();
    }

    private ProviderConfigView toView(ProviderFactory factory, ProviderConfig override) {
        boolean hasCustomKey = override != null && override.hasCustomApiKey();
        boolean enabled      = override != null && override.getEnabled() != null
                ? override.getEnabled()
                : factory.isEnabled();

        String modelAnalysis  = override != null && override.getModelAnalysis() != null
                ? override.getModelAnalysis()
                : factory.defaultModelAnalysis();

        String modelMigration = override != null && override.getModelMigration() != null
                ? override.getModelMigration()
                : factory.defaultModelMigration();

        return new ProviderConfigView(
                factory.providerId(),
                factory.costTier(),
                enabled,
                hasCustomKey,
                modelAnalysis,
                modelMigration,
                override != null ? override.getUpdatedAt() : null
        );
    }
}
