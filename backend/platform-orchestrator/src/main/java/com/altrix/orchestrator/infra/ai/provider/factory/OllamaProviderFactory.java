package com.altrix.orchestrator.infra.ai.provider.factory;

import com.altrix.orchestrator.infrastructure.config.AiProvidersConfig;
import com.altrix.orchestrator.infra.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infra.ai.provider.ProviderConfigResolver;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.provider.ResolvedProviderConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Local Ollama — no API key needed, always classified as FREE tier. */
@Slf4j
@Component
public class OllamaProviderFactory implements ProviderFactory {

    public static final String ID = "ollama";

    private final AiProvidersConfig.OllamaConfig systemCfg;
    private final ProviderConfigResolver resolver;

    public OllamaProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.ollama();
        this.resolver = resolver;
        log.info("OllamaProviderFactory ready — url={}", systemCfg.baseUrl());
    }

    @Override public String providerId()           { return ID; }
    @Override public ProviderCostTier costTier()            { return ProviderCostTier.FREE; }
    @Override public String defaultModelAnalysis() { return systemCfg.modelAnalysis(); }
    @Override public String defaultModelMigration(){ return systemCfg.modelMigration(); }

    @Override
    public boolean isEnabled() {
        ResolvedProviderConfig cfg = resolver.resolveOllama(systemCfg);
        return cfg.isEffectivelyEnabled(false);   // no API key required
    }

    @Override
    public RegisteredProvider build() {
        ResolvedProviderConfig cfg = resolver.resolveOllama(systemCfg);
        return new RegisteredProvider(
                ID,
                ProviderCostTier.FREE,
                model(cfg.modelAnalysis(), cfg),
                model(cfg.modelMigration(), cfg)
        );
    }

    private ChatLanguageModel model(String modelName, ResolvedProviderConfig cfg) {
        return OllamaChatModel.builder()
                .baseUrl(cfg.baseUrl())
                .modelName(modelName)
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .build();
    }
}
