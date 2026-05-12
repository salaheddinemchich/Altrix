package com.altrix.orchestrator.infra.ai;

import com.altrix.orchestrator.domain.port.out.ProviderRefreshPort;
import com.altrix.orchestrator.infra.ai.provider.RegisteredProvider;
import com.altrix.orchestrator.infra.ai.provider.factory.ProviderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers and holds the active AI provider pool.
 *
 * <p>Implements {@link ProviderRefreshPort} so the domain service can trigger
 * a live reload after a config change — no server restart needed.
 *
 * <p>The provider list is held in an {@link AtomicReference} for safe
 * concurrent reads during a refresh. Reads are always non-blocking.
 *
 * <p>This class contains ZERO provider-specific code (Open/Closed Principle).
 * Adding a new provider means adding a new {@code @Component ProviderFactory}.
 */
@Slf4j
@Component
public class ProviderRegistry implements ProviderRefreshPort {

    private final List<ProviderFactory> factories;
    private final AtomicReference<List<RegisteredProvider>> providers;

    public ProviderRegistry(List<ProviderFactory> factories) {
        this.factories = List.copyOf(factories);
        this.providers = new AtomicReference<>(build(factories));
    }

    public List<RegisteredProvider> all() {
        return providers.get();
    }

    /**
     * Rebuilds the provider pool from the current config (DB overrides + YAML defaults).
     * Safe to call at runtime — a single atomic reference swap replaces the old pool.
     * If no provider is enabled after the rebuild, the existing pool is kept.
     */
    @Override
    public void refreshProviders() {
        List<RegisteredProvider> rebuilt = build(factories);
        if (rebuilt.isEmpty()) {
            log.warn("Provider refresh produced an empty pool — keeping existing providers");
            return;
        }
        providers.set(rebuilt);
        log.info("ProviderRegistry refreshed — {} active provider(s): {}",
                rebuilt.size(), rebuilt.stream().map(RegisteredProvider::id).toList());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static List<RegisteredProvider> build(List<ProviderFactory> factories) {
        List<RegisteredProvider> built = factories.stream()
                .filter(ProviderFactory::isEnabled)
                .map(f -> {
                    RegisteredProvider p = f.build();
                    log.info("Provider registered: id={} costTier={}", p.id(), p.costTier());
                    return p;
                })
                .toList();

        if (built.isEmpty()) {
            log.warn("No AI provider is enabled — AI features will be unavailable. " +
                     "Set at least one API key under ai.providers.* in application.yml.");
        }
        return built;
    }
}
