/**
 * Cross-session aggregate metrics returned by
 * {@code GET /api/v1/reports/summary} (#131).
 */
export interface OrgSummary {
  totalSessions: number;
  /** Range [0,1].  0 when totalSessions is 0. */
  successRate: number;
  /** Mean (updatedAt − createdAt) of DONE sessions, in seconds. */
  averageDurationSeconds: number;
  /** Per-{@link import('./session.model').SessionStatus} row counts. */
  countsByStatus: Record<string, number>;
}
