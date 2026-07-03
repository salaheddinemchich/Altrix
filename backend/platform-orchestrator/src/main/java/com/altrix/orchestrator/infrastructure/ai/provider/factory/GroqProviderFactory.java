package com.altrix.orchestrator.infrastructure.ai.provider.factory;

import com.altrix.orchestrator.infrastructure.ai.provider.ProviderConfigResolver;
import com.altrix.orchestrator.infrastructure.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infrastructure.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infrastructure.ai.provider.ResolvedProviderConfig;
import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class GroqProviderFactory implements ProviderFactory {

    public static final String ID = "groq";

    private final AiProvidersConfig.OpenAiCompatibleConfig systemCfg;
    private final ProviderConfigResolver resolver;

    public GroqProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.groq();
        this.resolver = resolver;
        if (systemCfg.enabled() && systemCfg.apiKey().isBlank()) {
            log.warn("ai.providers.groq.enabled=true but GROQ_API_KEY is not set — " +
                    "provider will be skipped unless a key override is stored in the database.");
        }
        log.info("GroqProviderFactory ready — system key={}",
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
