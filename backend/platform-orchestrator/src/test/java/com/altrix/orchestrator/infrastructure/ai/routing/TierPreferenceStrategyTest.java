package com.altrix.orchestrator.infrastructure.ai.routing;

import com.altrix.orchestrator.infrastructure.ai.provider.ProviderCostTier;
import com.altrix.orchestrator.infrastructure.ai.provider.ProviderTier;
import com.altrix.orchestrator.infrastructure.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infrastructure.config.AiRoutingConfig.TierPreference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TierPreferenceStrategyTest {

    private static RegisteredProvider p(String id, ProviderCostTier costTier) {
        return new RegisteredProvider(id, costTier, null, null);
    }

    private final List<RegisteredProvider> mixed = List.of(
            p("groq", ProviderCostTier.FREE),
            p("openai", ProviderCostTier.PAID),
            p("deepseek", ProviderCostTier.FREE),
            p("anthropic", ProviderCostTier.PAID)
    );

    @Test
    void paid_first_puts_paid_before_free() {
        List<RegisteredProvider> result =
                new TierPreferenceStrategy(TierPreference.PAID_FIRST).order(mixed, ProviderTier.MIGRATION);

        assertThat(result).extracting(RegisteredProvider::id)
                .containsExactly("openai", "anthropic", "groq", "deepseek");
    }

    @Test
    void free_first_puts_free_before_paid() {
        List<RegisteredProvider> result =
                new TierPreferenceStrategy(TierPreference.FREE_FIRST).order(mixed, ProviderTier.ANALYSIS);

        assertThat(result).extracting(RegisteredProvider::id)
                .containsExactly("groq", "deepseek", "openai", "anthropic");
    }

    @Test
    void preserves_registration_order_within_same_tier() {
        List<RegisteredProvider> result =
                new TierPreferenceStrategy(TierPreference.PAID_FIRST).order(mixed, ProviderTier.MIGRATION);

        List<String> paidIds = result.stream()
                .filter(p -> p.costTier() == ProviderCostTier.PAID)
                .map(RegisteredProvider::id)
                .toList();
        assertThat(paidIds).containsExactly("openai", "anthropic");
    }

    @Test
    void empty_available_returns_empty() {
        assertThat(new TierPreferenceStrategy(TierPreference.PAID_FIRST)
                .order(List.of(), ProviderTier.MIGRATION)).isEmpty();
    }
}
