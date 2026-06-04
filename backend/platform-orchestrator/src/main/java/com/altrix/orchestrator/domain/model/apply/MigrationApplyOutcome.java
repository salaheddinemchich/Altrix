package com.altrix.orchestrator.domain.model.apply;

/**
 * Terminal state of a migration-apply attempt — what actually happened
 * in the repository after the user confirmed.  Mirrored on the
 * frontend via the JSON enum value so the UI can render the right
 * success / failure banner.
 */
public enum MigrationApplyOutcome {
    /** New branch created and migrated files pushed; no PR opened. */
    BRANCH_CREATED,
    /** Branch created + push + Pull Request opened against the default branch. */
    PR_CREATED,
    /** Migration was committed and merged into the default branch. */
    MERGED_TO_MAIN,
    /** User cancelled at or after the final confirmation gate. */
    CANCELLED,
    /** Provider returned an error — payload contains the reason. */
    FAILED
}
