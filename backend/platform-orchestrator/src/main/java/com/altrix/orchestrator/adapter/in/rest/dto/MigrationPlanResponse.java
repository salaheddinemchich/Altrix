package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.model.MigrationPlan;

import java.util.List;

/**
 * Subset of {@link MigrationPlan} exposed to the frontend so a reviewer can
 * inspect what the AI has proposed at the AWAITING_APPROVAL gate (#10).
 *
 * <p>{@code storageKey} is intentionally NOT carried — that is an internal
 * MinIO reference and has no UI value.
 */
public record MigrationPlanResponse(
        String targetStack,
        List<String> steps,
        String riskLevel,
        String estimatedEffort,
        String summary,
        List<String> targetFiles
) {
    public static MigrationPlanResponse from(MigrationPlan plan) {
        if (plan == null) return null;
        return new MigrationPlanResponse(
                plan.targetStack(),
                plan.steps(),
                plan.riskLevel(),
                plan.estimatedEffort(),
                plan.summary(),
                plan.targetFiles());
    }
}
