package com.altrix.orchestrator.infra.ai.provider;

import com.altrix.orchestrator.domain.model.provider.ProviderConfig;
import com.altrix.orchestrator.domain.port.out.ApiKeyEncryptionPort;
import com.altrix.orchestrator.domain.port.out.ProviderConfigRepositoryPort;
import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Merges system defaults (application.yml) with user overrides (database).
 * DB values win when present; fallback to YAML otherwise.
 *
 * <p>Factories call this inside {@code build()} so a registry refresh picks
 * up the latest merged config without restarting the application.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderConfigResolver {

    private final ProviderConfigRepositoryPort configRepository;
    private final ApiKeyEncryptionPort encryption;

    public ResolvedProviderConfig resolveOpenAiCompatible(
            String providerId,
            AiProvidersConfig.OpenAiCompatibleConfig system) {

        Optional<ProviderConfig> override = configRepository.findById(providerId);

        boolean enabled = override.map(ProviderConfig::getEnabled)
                .filter(e -> e != null)
                .orElse(system.enabled());

        String apiKey = override
                .filter(ProviderConfig::hasCustomApiKey)
                .map(o -> safeDecrypt(providerId, o.getEncryptedApiKey()))
                .orElse(system.apiKey());

        String baseUrl = override
                .map(ProviderConfig::getBaseUrl)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.baseUrl());

        String modelAnalysis = override
                .map(ProviderConfig::getModelAnalysis)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.modelAnalysis());

        String modelMigration = override
                .map(ProviderConfig::getModelMigration)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.modelMigration());

        return new ResolvedProviderConfig(
                enabled, apiKey, baseUrl,
                modelAnalysis, modelMigration,
                system.temperature(), system.timeoutSeconds(),
                system.maxTokens());
    }

    public ResolvedProviderConfig resolveAnthropic(AiProvidersConfig.AnthropicConfig system) {
        Optional<ProviderConfig> override = configRepository.findById("anthropic");

        boolean enabled = override.map(ProviderConfig::getEnabled)
                .filter(e -> e != null)
                .orElse(system.enabled());

        String apiKey = override
                .filter(ProviderConfig::hasCustomApiKey)
                .map(o -> safeDecrypt("anthropic", o.getEncryptedApiKey()))
                .orElse(system.apiKey());

        String modelAnalysis = override
                .map(ProviderConfig::getModelAnalysis)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.modelAnalysis());

        String modelMigration = override
                .map(ProviderConfig::getModelMigration)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.modelMigration());

        return new ResolvedProviderConfig(
                enabled, apiKey, "",
                modelAnalysis, modelMigration,
                system.temperature(), system.timeoutSeconds(),
                system.maxTokens());
    }

    public ResolvedProviderConfig resolveOllama(AiProvidersConfig.OllamaConfig system) {
        Optional<ProviderConfig> override = configRepository.findById("ollama");

        boolean enabled = override.map(ProviderConfig::getEnabled)
                .filter(e -> e != null)
                .orElse(system.enabled());

        String baseUrl = override
                .map(ProviderConfig::getBaseUrl)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.baseUrl());

        String modelAnalysis = override
                .map(ProviderConfig::getModelAnalysis)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.modelAnalysis());

        String modelMigration = override
                .map(ProviderConfig::getModelMigration)
                .filter(s -> s != null && !s.isBlank())
                .orElse(system.modelMigration());

        return new ResolvedProviderConfig(
                enabled, "", baseUrl,
                modelAnalysis, modelMigration,
                0.0, system.timeoutSeconds(),
                system.maxTokens());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String safeDecrypt(String providerId, String encryptedKey) {
        try {
            return encryption.decrypt(encryptedKey);
        } catch (Exception e) {
            log.error("Failed to decrypt API key for provider [{}] — falling back to system key", providerId);
            return "";
        }
    }
}
