package com.altrix.common.domain.model;

import java.util.List;

/**
 * Output of {@code MigrationPlannerAgent} (Agent 2).
 *
 * <p>Minimal placeholder — the rich schema (steps, file ops, transformations,
 * risks, estimated effort) lands in issue #5. This shape is enough for
 * Agent 3 to consume an {@link ApprovedPlan} wrapper without forcing the
 * planner sub-issues to depend on a finalised schema.
 */
public record MigrationPlan(

        String projectId,

        /** Ordered, free-form description of each migration step. */
        List<String> steps,

        /** Short summary the orchestrator persists alongside the job. */
        String summary

) {
    public MigrationPlan {
        steps   = steps   != null ? List.copyOf(steps) : List.of();
        summary = summary != null ? summary : "";
    }

    public static MigrationPlan empty(String projectId) {
        return new MigrationPlan(projectId, List.of(), "");
    }
}
