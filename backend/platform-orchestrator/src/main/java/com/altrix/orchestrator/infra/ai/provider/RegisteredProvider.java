package com.altrix.orchestrator.infra.ai.provider;

import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * Immutable value type holding a provider's identity and its two LangChain4j
 * models (one per call tier). Constructed once at startup by a
 * {@link com.altrix.orchestrator.infra.ai.provider.factory.ProviderFactory}.
 */
public record RegisteredProvider(
        String            id,
        ProviderCostTier  costTier,
        ChatLanguageModel analysisModel,
        ChatLanguageModel migrationModel
) {
    public ChatLanguageModel modelFor(ProviderTier callTier) {
        return callTier == ProviderTier.ANALYSIS ? analysisModel : migrationModel;
    }
}
