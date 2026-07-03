package com.altrix.orchestrator.infrastructure.ai.provider.factory;

import com.altrix.orchestrator.infrastructure.ai.provider.ProviderConfigResolver;
import com.altrix.orchestrator.infrastructure.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infrastructure.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infrastructure.ai.provider.ResolvedProviderConfig;
import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DeepSeek uses an OpenAI-compatible REST API (same format, different base URL).
 * Classified as FREE tier — has a generous free quota, significantly cheaper
 * than OpenAI/Anthropic even when paid.
 */
@Slf4j
@Component
public class DeepSeekProviderFactory implements ProviderFactory {

    public static final String ID = "deepseek";

    private final AiProvidersConfig.OpenAiCompatibleConfig systemCfg;
    private final ProviderConfigResolver resolver;

    public DeepSeekProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.deepseek();
        this.resolver = resolver;
        log.info("DeepSeekProviderFactory ready — system key={}",
                systemCfg.apiKey().isBlank() ? "[NOT SET]" : "[CONFIGURED]");
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public ProviderCostTier costTier() {
        return ProviderCostTier.FREE;
    }

    @Override
    public String defaultModelAnalysis() {
        return systemCfg.modelAnalysis();
    }

    @Override
    public String defaultModelMigration() {
        return systemCfg.modelMigration();
    }

    @Override
    public boolean isEnabled() {
        ResolvedProviderConfig cfg = resolver.resolveOpenAiCompatible(ID, systemCfg);
        return cfg.isEffectivelyEnabled(true);
    }

    @Override
    public RegisteredProvider build() {
        ResolvedProviderConfig cfg = resolver.resolveOpenAiCompatible(ID, systemCfg);
        return OpenAiCompatibleBuilder.build(ID, ProviderCostTier.FREE, cfg);
    }
}
