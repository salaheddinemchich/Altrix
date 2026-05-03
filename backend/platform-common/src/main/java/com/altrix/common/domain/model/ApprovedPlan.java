package com.altrix.common.domain.model;

import java.time.Instant;

/**
 * Input to {@code CoreMigratorAgent} (Agent 3).
 *
 * <p>A {@link MigrationPlan} that has cleared the human-in-the-loop approval
 * gate (issue #10). The wrapper records who approved and when so the
 * downstream report can attribute the decision.
 */
public record ApprovedPlan(

        MigrationPlan plan,

        /** User identity that approved the plan. {@code "auto"} when approval is stubbed. */
        String approvedBy,

        Instant approvedAt

) {
    public ApprovedPlan {
        if (plan == null)        throw new IllegalArgumentException("plan must not be null");
        if (approvedBy == null)  approvedBy = "auto";
        if (approvedAt == null)  approvedAt = Instant.EPOCH;
    }

    public static ApprovedPlan autoApproved(MigrationPlan plan) {
        return new ApprovedPlan(plan, "auto", Instant.now());
    }
}
