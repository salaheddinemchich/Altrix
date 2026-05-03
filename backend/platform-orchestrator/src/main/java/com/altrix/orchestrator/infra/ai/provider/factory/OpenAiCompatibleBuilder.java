package com.altrix.orchestrator.infra.ai.provider.factory;

import com.altrix.orchestrator.infra.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.provider.ResolvedProviderConfig;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;

/**
 * Static builder for any provider that exposes an OpenAI-compatible REST API
 * (Groq, OpenAI, DeepSeek, Together.ai, etc.).
 *
 * <p>Not a Spring bean — purely a construction helper so the three concrete
 * factories share the same model-building logic without inheritance.
 * Accepts a {@link ResolvedProviderConfig} that already has DB overrides merged in.
 */
final class OpenAiCompatibleBuilder {

    private OpenAiCompatibleBuilder() {}

    static RegisteredProvider build(String id, ProviderCostTier costTier, ResolvedProviderConfig cfg) {
        return new RegisteredProvider(
                id,
                costTier,
                model(cfg.baseUrl(), cfg.apiKey(), cfg.modelAnalysis(),  cfg),
                model(cfg.baseUrl(), cfg.apiKey(), cfg.modelMigration(), cfg)
        );
    }

    private static ChatLanguageModel model(
            String baseUrl, String apiKey, String modelName, ResolvedProviderConfig cfg) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(cfg.temperature())
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }
}
