export type SessionStatus =
  | 'PENDING'
  | 'CONTEXT_ANALYSED'
  | 'PLAN_READY'
  | 'AWAITING_APPROVAL'
  | 'MIGRATING'
  | 'VALIDATING'
  | 'DONE'
  | 'FAILED'
  | 'PAUSED';

export interface MigrationPlan {
  targetStack: string;
  steps: string[];
  riskLevel: string;
  estimatedEffort: string;
  summary: string;
  targetFiles: string[];
}

export interface Session {
  sessionId: string;
  jobId: string;
  projectId: string;
  status: SessionStatus;
  pausedFrom: SessionStatus | null;
  errorMessage: string | null;
  updatedAt: string;
  /** Issue #10 — populated from PLAN_READY onwards. Null on early/legacy rows. */
  plan: MigrationPlan | null;
}

export interface SessionPage {
  content: Session[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface MigratedFile {
  originalPath: string;
  newPath: string;
  changeType: string;
  diffSummary: string | null;
  content: string;
}

/** #119 — both sides of the diff for a single file. */
export interface FileDiff {
  originalPath: string;
  newPath: string;
  changeType: string;
  /** null for CREATED files (nothing existed before). */
  originalContent: string | null;
  /** null for DELETED files (nothing remains after). */
  migratedContent: string | null;
  diffSummary: string | null;
}

export interface PauseRecord {
  id: number;
  pausedFrom: string;
  pausedAt: string;
  resumedAt: string | null;
}
