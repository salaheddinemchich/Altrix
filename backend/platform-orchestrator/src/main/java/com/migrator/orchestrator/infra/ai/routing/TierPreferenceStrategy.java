package com.migrator.orchestrator.infra.ai.routing;

import com.migrator.orchestrator.infrastructure.config.AiRoutingConfig.TierPreference;
import com.migrator.orchestrator.infra.ai.provider.ProviderCostTier;
import com.migrator.orchestrator.infra.ai.provider.ProviderTier;
import com.migrator.orchestrator.infra.ai.provider.RegisteredProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * Orders providers by tier preference:
 * <ul>
 *   <li>{@link TierPreference#PAID_FIRST} — best quality first, falls back to free.</li>
 *   <li>{@link TierPreference#FREE_FIRST} — cheapest first, escalates to paid on failure.</li>
 * </ul>
 * Within each tier the original registration order is preserved.
 */
public final class TierPreferenceStrategy implements ProviderSelectionStrategy {

    private final TierPreference preference;

    public TierPreferenceStrategy(TierPreference preference) {
        this.preference = preference;
    }

    @Override
    public List<RegisteredProvider> order(List<RegisteredProvider> available, ProviderTier tier) {
        ProviderCostTier preferred = (preference == TierPreference.PAID_FIRST)
                ? ProviderCostTier.PAID
                : ProviderCostTier.FREE;

        List<RegisteredProvider> first  = new ArrayList<>();
        List<RegisteredProvider> second = new ArrayList<>();

        for (RegisteredProvider p : available) {
            if (p.costTier() == preferred) {
                first.add(p);
            } else {
                second.add(p);
            }
        }

        List<RegisteredProvider> result = new ArrayList<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }
}
