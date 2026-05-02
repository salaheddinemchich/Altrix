package com.migrator.orchestrator.infra.ai.provider;

import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * A resolved AI provider pairing a provider ID with the two LangChain4j models
 * it exposes (one per tier). Immutable value type — constructed once at startup.
 */
public record RegisteredProvider(
        String            id,
        boolean           free,
        ChatLanguageModel analysisModel,
        ChatLanguageModel migrationModel
) {
    public ChatLanguageModel modelFor(ProviderTier tier) {
        return tier == ProviderTier.ANALYSIS ? analysisModel : migrationModel;
    }
}
