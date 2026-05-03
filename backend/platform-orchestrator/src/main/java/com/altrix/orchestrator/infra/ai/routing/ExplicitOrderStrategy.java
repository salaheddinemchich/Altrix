package com.altrix.orchestrator.infra.ai.routing;

import com.altrix.orchestrator.infra.ai.provider.ProviderTier;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orders providers exactly as specified in {@code ai.routing.explicit-order}.
 * Providers absent from the explicit list are appended at the end in their
 * original registration order (so new providers are never silently dropped).
 */
public final class ExplicitOrderStrategy implements ProviderSelectionStrategy {

    private final List<String> explicitOrder;

    public ExplicitOrderStrategy(List<String> explicitOrder) {
        this.explicitOrder = List.copyOf(explicitOrder);
    }

    @Override
    public List<RegisteredProvider> order(List<RegisteredProvider> available, ProviderTier tier) {
        Map<String, RegisteredProvider> byId = available.stream()
                .collect(Collectors.toMap(RegisteredProvider::id, Function.identity()));

        List<RegisteredProvider> result = explicitOrder.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .collect(Collectors.toCollection(java.util.ArrayList::new));

        // Append any providers not in the explicit list (preserves future extensibility)
        available.stream()
                .filter(p -> !explicitOrder.contains(p.id()))
                .forEach(result::add);

        return List.copyOf(result);
    }
}
