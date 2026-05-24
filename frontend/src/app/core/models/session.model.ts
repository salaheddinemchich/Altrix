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
  /** #125 — JWT sub of the reviewer who answered the approval gate. */
  decidedBy: string | null;
  /** #125 — when the approval / rejection decision was recorded. */
  decidedAt: string | null;
  /** #125 — 'APPROVED' | 'REJECTED' — null until decided. */
  decisionKind: 'APPROVED' | 'REJECTED' | null;
}

/**
 * RAG index manifest — which source files were indexed for retrieval
 * during a session.  Returned by GET /api/v1/sessions/{id}/rag-index.
 * chunkCount = 0 means the embedding model was disabled at index time
 * (filePaths still populated with what WOULD have been indexed).
 */
export interface RagIndexManifest {
  projectId: string;
  filePaths: string[];
  fileCount: number;
  chunkCount: number;
  indexedAt: string;
}

/** #126 — one entry of the approval-history timeline for a session. */
export interface ApprovalHistoryEntry {
  decidedBy: string;
  decidedAt: string;
  decisionKind: 'APPROVED' | 'REJECTED';
  /** Rejection reason — null for APPROVED entries. */
  reason: string | null;
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

/**
 * One entry of the project-wide file tree returned by
 * `GET /api/v1/sessions/{id}/files/tree`.  status one of:
 * MODIFIED | CREATED | DELETED | UNCHANGED | UNTOUCHED.
 */
export interface SessionFileNode {
  path: string;
  status: 'MODIFIED' | 'CREATED' | 'DELETED' | 'UNCHANGED' | 'UNTOUCHED' | string;
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
