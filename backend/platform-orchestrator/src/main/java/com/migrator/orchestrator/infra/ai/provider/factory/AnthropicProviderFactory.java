package com.migrator.orchestrator.infra.ai.provider.factory;

import com.migrator.orchestrator.infrastructure.config.AiProvidersConfig;
import com.migrator.orchestrator.infra.ai.provider.ProviderCostTier;
import com.migrator.orchestrator.infra.ai.provider.ProviderConfigResolver;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;
import com.migrator.orchestrator.infra.ai.provider.ResolvedProviderConfig;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
public class AnthropicProviderFactory implements ProviderFactory {

    public static final String ID = "anthropic";

    private final AiProvidersConfig.AnthropicConfig systemCfg;
    private final ProviderConfigResolver            resolver;

    public AnthropicProviderFactory(AiProvidersConfig config, ProviderConfigResolver resolver) {
        this.systemCfg = config.anthropic();
        this.resolver  = resolver;
        log.info("AnthropicProviderFactory ready — system key={}",
                systemCfg.apiKey().isBlank() ? "[NOT SET]" : "[CONFIGURED]");
    }

    @Override public String          providerId()           { return ID; }
    @Override public ProviderCostTier costTier()            { return ProviderCostTier.PAID; }
    @Override public String          defaultModelAnalysis() { return systemCfg.modelAnalysis(); }
    @Override public String          defaultModelMigration(){ return systemCfg.modelMigration(); }

    @Override
    public boolean isEnabled() {
        ResolvedProviderConfig cfg = resolver.resolveAnthropic(systemCfg);
        return cfg.isEffectivelyEnabled(true);
    }

    @Override
    public RegisteredProvider build() {
        ResolvedProviderConfig cfg = resolver.resolveAnthropic(systemCfg);
        return new RegisteredProvider(
                ID,
                ProviderCostTier.PAID,
                model(cfg.modelAnalysis(), cfg),
                model(cfg.modelMigration(), cfg)
        );
    }

    private ChatLanguageModel model(String modelName, ResolvedProviderConfig cfg) {
        return AnthropicChatModel.builder()
                .apiKey(cfg.apiKey())
                .modelName(modelName)
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .build();
    }
}
