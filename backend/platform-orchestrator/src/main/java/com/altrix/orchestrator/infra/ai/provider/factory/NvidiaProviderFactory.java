package com.altrix.orchestrator.infra.ai.provider.factory;

import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import com.altrix.orchestrator.infra.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infra.ai.provider.ProviderConfigResolver;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.provider.ResolvedProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * NVIDIA NIM — OpenAI-compatible API at integrate.api.nvidia.com/v1.
 * Get an API key at https://build.nvidia.com/models.
 * Classified as PAID (per-token billing after the free-trial credits).
 */
@Slf4j
@Component
public class NvidiaProviderFactory implements ProviderFactory {

    public static final String ID = "nvidia";

    private final AiProvidersConfig.OpenAiCompatibleConfig systemCfg;
    private final ProviderConfigResolver                   resolver;

    public NvidiaProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.nvidia();
        this.resolver  = resolver;
        if (systemCfg.enabled() && systemCfg.apiKey().isBlank()) {
            log.warn("ai.providers.nvidia.enabled=true but NVIDIA_API_KEY is not set — " +
                     "provider will be skipped unless a key override is stored in the database.");
        }
        log.info("NvidiaProviderFactory ready — system key={}",
                systemCfg.apiKey().isBlank() ? "[NOT SET]" : "[CONFIGURED]");
    }

    @Override public String          providerId()            { return ID; }
    @Override public ProviderCostTier costTier()             { return ProviderCostTier.PAID; }
    @Override public String          defaultModelAnalysis()  { return systemCfg.modelAnalysis(); }
    @Override public String          defaultModelMigration() { return systemCfg.modelMigration(); }

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
