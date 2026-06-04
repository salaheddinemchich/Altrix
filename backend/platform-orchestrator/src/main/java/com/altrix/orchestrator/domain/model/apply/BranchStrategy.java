package com.altrix.orchestrator.domain.model.apply;

/**
 * How the user wants the migration result to land in the repository
 * once the AI pipeline is complete and the user has reviewed it.
 *
 * <p>Each value is realised by a dedicated {@code BranchStrategyHandler}
 * (strategy pattern) so adding a new strategy is a single new handler
 * rather than touching switch statements in the orchestrator.
 *
 * <ul>
 *   <li>{@link #DIRECT_MERGE}   — commit migration straight to the default
 *       branch.  Only offered to users with {@code ADMIN} permission on
 *       the repository.  Highest risk; auditable.</li>
 *   <li>{@link #NEW_BRANCH}     — create a separate branch with the
 *       migration result.  No PR is opened.  Safe default.</li>
 *   <li>{@link #PULL_REQUEST}   — create a branch + open a Pull Request
 *       targeting the default branch.  Used when reviewer approval is
 *       required before merge.</li>
 * </ul>
 */
public enum BranchStrategy {
    DIRECT_MERGE,
    NEW_BRANCH,
    PULL_REQUEST
}
