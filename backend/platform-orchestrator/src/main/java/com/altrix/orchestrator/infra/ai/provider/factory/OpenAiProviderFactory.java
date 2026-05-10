package com.altrix.orchestrator.infra.ai.provider.factory;

import com.altrix.orchestrator.infra.ai.provider.ProviderConfigResolver;
import com.altrix.orchestrator.infra.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.provider.ResolvedProviderConfig;
import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OpenAiProviderFactory implements ProviderFactory {

    public static final String ID = "openai";

    private final AiProvidersConfig.OpenAiCompatibleConfig systemCfg;
    private final ProviderConfigResolver resolver;

    public OpenAiProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.openai();
        this.resolver = resolver;
        log.info("OpenAiProviderFactory ready — system key={}",
                systemCfg.apiKey().isBlank() ? "[NOT SET]" : "[CONFIGURED]");
    }

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public ProviderCostTier costTier() {
        return ProviderCostTier.PAID;
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
        return OpenAiCompatibleBuilder.build(ID, ProviderCostTier.PAID, cfg);
    }
}
