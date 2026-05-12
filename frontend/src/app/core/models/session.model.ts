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

export interface Session {
  sessionId: string;
  jobId: string;
  projectId: string;
  status: SessionStatus;
  pausedFrom: SessionStatus | null;
  errorMessage: string | null;
  updatedAt: string;
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

export interface PauseRecord {
  id: number;
  pausedFrom: string;
  pausedAt: string;
  resumedAt: string | null;
}
