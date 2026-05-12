package com.altrix.orchestrator.infra.ai.provider.factory;

import com.altrix.orchestrator.infra.ai.provider.ProviderConfigResolver;
import com.altrix.orchestrator.infra.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.provider.ResolvedProviderConfig;
import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenRouter — unified gateway to dozens of LLMs via an OpenAI-compatible API
 * at https://openrouter.ai/api/v1. Treated as PAID by default; per-token costs
 * vary per upstream model. Useful when you want a single key to access models
 * from multiple labs (OpenAI, Anthropic, Mistral, Meta, …) without juggling
 * separate accounts.
 *
 * <p>Get a key at https://openrouter.ai/keys.
 */
@Slf4j
@Component
public class OpenRouterProviderFactory implements ProviderFactory {

    public static final String ID = "openrouter";

    private final AiProvidersConfig.OpenAiCompatibleConfig systemCfg;
    private final ProviderConfigResolver resolver;

    public OpenRouterProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.openrouter();
        this.resolver = resolver;
        if (systemCfg.enabled() && systemCfg.apiKey().isBlank()) {
            log.warn("ai.providers.openrouter.enabled=true but OPENROUTER_API_KEY is not set — " +
                    "provider will be skipped unless a key override is stored in the database.");
        }
        log.info("OpenRouterProviderFactory ready — system key={}",
                systemCfg.apiKey().isBlank() ? "[NOT SET]" : "[CONFIGURED]");
    }

    @Override public String providerId()             { return ID; }
    @Override public ProviderCostTier costTier()     { return ProviderCostTier.PAID; }
    @Override public String defaultModelAnalysis()   { return systemCfg.modelAnalysis(); }
    @Override public String defaultModelMigration()  { return systemCfg.modelMigration(); }

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
