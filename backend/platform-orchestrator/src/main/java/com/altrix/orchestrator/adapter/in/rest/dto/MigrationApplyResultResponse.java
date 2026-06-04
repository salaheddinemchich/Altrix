package com.altrix.orchestrator.adapter.in.rest.dto;

import com.altrix.orchestrator.domain.model.apply.MigrationApplyOutcome;
import com.altrix.orchestrator.domain.model.apply.MigrationApplyResult;

import java.time.Instant;

/**
 * REST shape of {@link MigrationApplyResult}.  Same fields, plus a
 * {@code success} convenience flag so the UI doesn't need to compare
 * enum values to decide whether to render a green or red banner.
 */
public record MigrationApplyResultResponse(
        MigrationApplyOutcome outcome,
        boolean success,
        String branchName,
        String commitSha,
        String prUrl,
        Integer prNumber,
        String mergeSha,
        String message,
        Instant completedAt
) {
    public static MigrationApplyResultResponse from(MigrationApplyResult r) {
        boolean success = switch (r.outcome()) {
            case BRANCH_CREATED, PR_CREATED, MERGED_TO_MAIN -> true;
            case CANCELLED, FAILED -> false;
        };
        return new MigrationApplyResultResponse(
                r.outcome(), success, r.branchName(), r.commitSha(), r.prUrl(),
                r.prNumber(), r.mergeSha(), r.message(), r.completedAt());
    }
}
