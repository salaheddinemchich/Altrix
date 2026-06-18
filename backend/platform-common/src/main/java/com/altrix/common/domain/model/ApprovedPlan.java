package com.altrix.common.domain.model;

import java.io.Serializable;

import java.time.Instant;

/**
 * Input to {@code CoreMigratorAgent} (Agent 3).
 *
 * <p>A {@link MigrationPlan} that has cleared the human-in-the-loop approval
 * gate (issue #10). The wrapper records who approved and when so the
 * downstream report can attribute the decision.
 *
 * <p>{@code retryContext} is {@code null} on the first attempt and is populated
 * by {@code RetryContextBuilder} on subsequent retries (#48). The migrator
 * prepends it to the system prompt to give the model targeted failure context.
 *
 * <p>{@code previousArtifact} carries the output of the previous migration
 * attempt so the migrator can start from that checkpoint rather than re-reading
 * the original source.  {@code null} on the first attempt.
 */
public record ApprovedPlan(

        MigrationPlan plan,

        /** User identity that approved the plan. {@code "auto"} when approval is stubbed. */
        String approvedBy,

        Instant approvedAt,

        /**
         * Token-budgeted failure summary from the previous validation attempt.
         * {@code null} on the first run; non-null when the workflow is retrying
         * after a sandbox validation failure.
         */
        String retryContext,

        /**
         * Migrated files from the previous attempt.  When non-null the migrator
         * overlays these over the original source so successfully-migrated files
         * are preserved even if AI providers are unavailable on this retry.
         * {@code null} on the first attempt.
         */
        MigrationArtifact previousArtifact

) implements Serializable {
    public ApprovedPlan {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        if (approvedBy == null) approvedBy = "auto";
        if (approvedAt == null) approvedAt = Instant.EPOCH;
        // retryContext and previousArtifact may be null — intentional
    }

    public static ApprovedPlan autoApproved(MigrationPlan plan) {
        return new ApprovedPlan(plan, "auto", Instant.now(), null, null);
    }

    public ApprovedPlan withRetryContext(String context) {
        return new ApprovedPlan(plan, approvedBy, approvedAt, context, null);
    }

    public ApprovedPlan withRetryContext(String context, MigrationArtifact artifact) {
        return new ApprovedPlan(plan, approvedBy, approvedAt, context, artifact);
    }
}
