package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.apply.BranchStrategy;
import com.altrix.orchestrator.domain.port.in.ChooseBranchStrategyUseCase.Outcome;

import java.util.UUID;

/**
 * Response to {@code POST /sessions/{id}/branch-strategy}.  Carries the
 * single-use {@code confirmationToken} the client must echo back when
 * calling {@code /apply} — see {@link com.altrix.orchestrator.domain.port.in.ConfirmAndApplyMigrationUseCase}.
 */
public record BranchStrategyResponse(
        UUID confirmationToken,
        BranchStrategy strategy,
        String branchName,
        String baseBranch
) {
    public static BranchStrategyResponse from(Outcome o) {
        return new BranchStrategyResponse(o.confirmationToken(), o.strategy(), o.targetBranchName(), o.baseBranch());
    }
}
