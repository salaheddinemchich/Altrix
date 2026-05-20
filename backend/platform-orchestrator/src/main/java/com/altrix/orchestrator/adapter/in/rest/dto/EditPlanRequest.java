package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.common.domain.model.MigrationPlan;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * HTTP body for {@code PATCH /api/v1/sessions/{id}/plan} (#10 follow-up).
 *
 * <p>Reviewer-editable fields only — {@code storageKey} and {@code projectId}
 * are intentionally NOT accepted; the service merges the edits on top of the
 * session's existing plan to preserve those internal references.
 */
public record EditPlanRequest(

        String targetStack,

        @NotNull List<String> steps,

        String riskLevel,

        String estimatedEffort,

        String summary,

        @NotNull List<String> targetFiles
) {
    /**
     * Builds a {@link MigrationPlan} carrying only the reviewer-editable
     * fields.  {@code projectId} / {@code storageKey} are blank here on
     * purpose — the service overlays them from the stored plan before
     * persisting.
     */
    public MigrationPlan toEditedPlan() {
        return new MigrationPlan(
                "", "",
                targetStack,
                steps,
                riskLevel,
                estimatedEffort,
                summary,
                targetFiles
        );
    }
}
