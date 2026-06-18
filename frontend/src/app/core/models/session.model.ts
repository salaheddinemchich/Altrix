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
 * One retrieved documentation chunk's identity + snippet (#1).
 * Mirrors the backend's FileProvenance.DocReference record.
 */
export interface DocReference {
  /** Logical doc bucket — e.g. "kafka/producers", "gcp-pubsub/overview". */
  logicalPath: string;
  /** Canonical URL the content was fetched from. */
  sourceUrl: string;
  /** First ~400 chars of the matched chunk text. */
  snippet: string;
}

/**
 * Per-file RAG provenance for a session (#1).  For each migrated file,
 * lists the doc chunks the AI used as context.  Powers the
 * "what docs informed this file" panel on the Index step.
 */
export interface FileProvenance {
  sessionId: string;
  /** Source-file path → ordered list of doc chunks (first = most relevant). */
  perFile: Record<string, DocReference[]>;
  generatedAt: string;
}

/**
 * One persisted sandbox-runner log (#105).  Drives the log-viewer
 * expansion on the Validate step of the JobDetail timeline.
 * exitCode = null for runners that don't produce one
 * (static / migration-quality).  exitCode = 137 is our timeout sentinel.
 */
export interface SandboxLog {
  runnerId: string;
  content: string;
  exitCode: number | null;
  generatedAt: string;
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

/** One per-runner result row inside {@link ReportSummary.validationStages}. */
export interface ValidationStageResult {
  runnerId: string;
  label: string;
  passed: boolean;
  errorCount: number;
  warningCount: number;
}

/** One detected risk inside {@link ReportSummary.risks}. */
export interface RiskItem {
  level: string;
  file: string;
  issue: string;
  recommendation: string;
}

/** One recorded migration decision inside {@link ReportSummary.decisions}. */
export interface DecisionLogEntry {
  kind: string;
  from: string;
  to: string;
  scope: string;
  rationale: string;
}

/**
 * Structured, fully deterministic companion to the Markdown report
 * content — every field is computed from real recorded pipeline data,
 * no AI narrative.  Mirrors the backend's ReportSummary record.
 */
export interface ReportSummary {
  status: string;
  confidenceScore: number;
  filesAnalyzed: number;
  filesModified: number;
  filesCreated: number;
  filesDeleted: number;
  filesUnchanged: number;
  compilePassed: boolean;
  bootPassed: boolean;
  testsPassed: boolean;
  targetStack: string;
  riskLevel: string;
  estimatedEffort: string;
  detectedComponents: string[];
  detectedIntegrations: string[];
  migrationSteps: string[];
  addedDependencies: string[];
  removedDependencies: string[];
  validationStages: ValidationStageResult[];
  risks: RiskItem[];
  manualActions: string[];
  decisions: DecisionLogEntry[];
  recommendation: string;
}

/**
 * The final migration report — returned by
 * GET /api/v1/sessions/{id}/report.  `content` is the full Markdown
 * document; `summary` is the same data in machine-readable form.
 */
export interface MigrationReport {
  projectId: string;
  content: string;
  generatedAt: string;
  summary: ReportSummary;
}
