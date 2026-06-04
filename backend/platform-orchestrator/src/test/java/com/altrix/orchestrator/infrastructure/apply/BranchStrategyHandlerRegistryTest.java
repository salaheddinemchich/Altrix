package com.altrix.orchestrator.infrastructure.apply;

import com.altrix.orchestrator.domain.model.apply.BranchFileChange;
import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyDecision;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchStrategyHandlerRegistryTest {

    private static BranchStrategyHandler stub(BranchStrategy s) {
        return new BranchStrategyHandler() {
            @Override public BranchStrategy strategy() { return s; }
            @Override public MigrationApplyResult apply(MigrationApplyDecision d, String t, String r, List<BranchFileChange> c) {
                return MigrationApplyResult.cancelled();
            }
        };
    }

    @Test
    void registry_resolvesEachStrategyToItsHandler() {
        var registry = new BranchStrategyHandlerRegistry(List.of(
                stub(BranchStrategy.DIRECT_MERGE),
                stub(BranchStrategy.NEW_BRANCH),
                stub(BranchStrategy.PULL_REQUEST)));
        assertThat(registry.handlerFor(BranchStrategy.NEW_BRANCH).strategy())
                .isEqualTo(BranchStrategy.NEW_BRANCH);
    }

    @Test
    void registry_failsAtBootWhenAStrategyIsUnregistered() {
        assertThatThrownBy(() -> new BranchStrategyHandlerRegistry(List.of(
                stub(BranchStrategy.NEW_BRANCH))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No BranchStrategyHandler");
    }

    @Test
    void registry_failsAtBootWhenTwoBeansClaimTheSameStrategy() {
        assertThatThrownBy(() -> new BranchStrategyHandlerRegistry(List.of(
                stub(BranchStrategy.NEW_BRANCH),
                stub(BranchStrategy.NEW_BRANCH),
                stub(BranchStrategy.PULL_REQUEST),
                stub(BranchStrategy.DIRECT_MERGE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple BranchStrategyHandler beans");
    }
}
