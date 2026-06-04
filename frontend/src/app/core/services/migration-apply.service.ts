import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  ApplyMigrationRequest,
  BranchStrategyRequest,
  BranchStrategyResponse,
  MigrationApplyResult,
  RepositoryAccess,
} from '../models/migration-apply.model';

/**
 * Migration Approval & Branch Strategy Workflow (#PR-feature).
 *
 * <p>All endpoints live under a dedicated {@code /migration-apply/}
 * namespace so they can't collide with the existing
 * {@code /projects/...} and {@code /sessions/...} controllers (the
 * earlier draft used {@code /projects/{id}/repo-access} which was
 * routed to Spring Security's OAuth2 login redirect and surfaced as a
 * CORS error in the browser).
 *
 * <p>Access is looked up by session id rather than project id — the
 * job-detail page already has the session, and the backend resolves
 * the project internally, keeping persistence details out of the
 * frontend.
 */
@Injectable({ providedIn: 'root' })
export class MigrationApplyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.orchestrator}/api/v1/migration-apply`;

  /** GET /migration-apply/sessions/{sid}/access — what the user can do on the repo. */
  getAccess(sessionId: string): Observable<RepositoryAccess> {
    return this.http.get<RepositoryAccess>(`${this.base}/sessions/${sessionId}/access`);
  }

  /**
   * POST /migration-apply/sessions/{sid}/strategy — user picks a strategy.
   * Returns the {@code confirmationToken} that must be echoed on /confirm.
   */
  setStrategy(sessionId: string, body: BranchStrategyRequest): Observable<BranchStrategyResponse> {
    return this.http.post<BranchStrategyResponse>(
      `${this.base}/sessions/${sessionId}/strategy`, body);
  }

  /** POST /migration-apply/sessions/{sid}/confirm — final confirmation gate. */
  confirm(sessionId: string, body: ApplyMigrationRequest): Observable<MigrationApplyResult> {
    return this.http.post<MigrationApplyResult>(
      `${this.base}/sessions/${sessionId}/confirm`, body);
  }

  /** POST /migration-apply/sessions/{sid}/cancel — back out without applying. */
  cancel(sessionId: string): Observable<MigrationApplyResult> {
    return this.http.post<MigrationApplyResult>(
      `${this.base}/sessions/${sessionId}/cancel`, {});
  }
}
