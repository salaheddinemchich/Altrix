/**
 * Migration Approval & Branch Strategy Workflow (#PR-feature).
 * Mirror of the backend's domain enums + REST DTOs.
 */

export type BranchStrategy = 'DIRECT_MERGE' | 'NEW_BRANCH' | 'PULL_REQUEST';

export type RepositoryPermission = 'NONE' | 'READ' | 'WRITE' | 'ADMIN';

export type MigrationApplyOutcome =
  | 'BRANCH_CREATED'
  | 'PR_CREATED'
  | 'MERGED_TO_MAIN'
  | 'CANCELLED'
  | 'FAILED';

/** GET /api/v1/projects/{id}/repo-access */
export interface RepositoryAccess {
  fullName: string;
  defaultBranch: string;
  permission: RepositoryPermission;
  canCreateBranch: boolean;
  canMergeDefault: boolean;
  /** Strategies the user is allowed to pick; rendered in this exact order. */
  availableStrategies: BranchStrategy[];
  /** Every branch on the repo, so the user can push to a base other than the default branch. */
  branches: string[];
}

/** Body of POST /api/v1/sessions/{id}/branch-strategy */
export interface BranchStrategyRequest {
  strategy: BranchStrategy;
  branchName?: string;
  baseBranch?: string;
  commitMessage?: string;
  prTitle?: string;
  prBody?: string;
}

/** Response of POST /api/v1/sessions/{id}/branch-strategy */
export interface BranchStrategyResponse {
  /** Server-issued single-use token; the apply call MUST echo this back. */
  confirmationToken: string;
  strategy: BranchStrategy;
  branchName: string | null;
  baseBranch: string;
}

/** Body of POST /api/v1/sessions/{id}/apply */
export interface ApplyMigrationRequest {
  strategy: BranchStrategy;
  branchName?: string;
  commitMessage?: string;
  prTitle?: string;
  prBody?: string;
  confirmationToken: string;
  /** Must be true — mirrors the required "I have reviewed and approve" checkbox. */
  userApproved: boolean;
}

/** Response of POST /api/v1/sessions/{id}/apply (or /apply/cancel) */
export interface MigrationApplyResult {
  outcome: MigrationApplyOutcome;
  success: boolean;
  branchName: string | null;
  commitSha: string | null;
  prUrl: string | null;
  prNumber: number | null;
  mergeSha: string | null;
  message: string | null;
  completedAt: string;
}
