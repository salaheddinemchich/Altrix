package com.altrix.orchestrator.domain.model.apply;

import java.time.Instant;

/**
 * The result of an apply attempt — returned by the REST endpoint and
 * persisted on the session for later inspection.  All provider-specific
 * URLs come out as plain strings; the domain doesn't carry GitHub /
 * GitLab types.
 *
 * @param outcome      coarse-grained state.
 * @param branchName   the branch the migration was committed to (null for FAILED).
 * @param commitSha    SHA of the commit produced (null for CANCELLED / FAILED).
 * @param prUrl        Pull-Request URL (PR_CREATED only).
 * @param prNumber     Pull-Request number (PR_CREATED only).
 * @param mergeSha     merge-commit SHA (MERGED_TO_MAIN only).
 * @param message      human-readable message — error reason on FAILED.
 * @param completedAt  when the apply attempt finished.
 */
public record MigrationApplyResult(
        MigrationApplyOutcome outcome,
        String branchName,
        String commitSha,
        String prUrl,
        Integer prNumber,
        String mergeSha,
        String message,
        Instant completedAt
) {
    public MigrationApplyResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        if (completedAt == null) completedAt = Instant.now();
    }

    /** Convenience for the cancelled / failure paths. */
    public static MigrationApplyResult failed(String reason) {
        return new MigrationApplyResult(MigrationApplyOutcome.FAILED, null, null, null, null, null, reason, Instant.now());
    }
    public static MigrationApplyResult cancelled() {
        return new MigrationApplyResult(MigrationApplyOutcome.CANCELLED, null, null, null, null, null, "User cancelled", Instant.now());
    }
}
