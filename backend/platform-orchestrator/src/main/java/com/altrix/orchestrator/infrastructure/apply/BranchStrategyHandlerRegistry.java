package com.altrix.orchestrator.infrastructure.apply;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers every {@link BranchStrategyHandler} bean at startup and
 * indexes them by strategy.  The orchestrator looks up the handler
 * for a chosen strategy here — no central {@code switch} statement
 * touches new strategies as they are added.
 *
 * <p>Initialised eagerly so a misconfigured handler (two beans for
 * the same strategy, or a missing one) fails at boot, not at the first
 * apply call.
 */
@Slf4j
@Component
public class BranchStrategyHandlerRegistry {

    private final Map<BranchStrategy, BranchStrategyHandler> byStrategy;

    public BranchStrategyHandlerRegistry(List<BranchStrategyHandler> handlers) {
        Map<BranchStrategy, BranchStrategyHandler> idx = new EnumMap<>(BranchStrategy.class);
        for (BranchStrategyHandler h : handlers) {
            BranchStrategy s = h.strategy();
            if (idx.putIfAbsent(s, h) != null) {
                throw new IllegalStateException("Multiple BranchStrategyHandler beans declared for " + s);
            }
        }
        for (BranchStrategy s : BranchStrategy.values()) {
            if (!idx.containsKey(s)) {
                throw new IllegalStateException("No BranchStrategyHandler bean for strategy " + s);
            }
        }
        this.byStrategy = Map.copyOf(idx);
        log.info("BranchStrategyHandlerRegistry ready — {} handlers: {}", handlers.size(), idx.keySet());
    }

    /** Resolves the handler for {@code strategy} or throws if none registered. */
    public BranchStrategyHandler handlerFor(BranchStrategy strategy) {
        BranchStrategyHandler h = byStrategy.get(strategy);
        if (h == null) throw new IllegalStateException("No handler for strategy " + strategy);
        return h;
    }
}
