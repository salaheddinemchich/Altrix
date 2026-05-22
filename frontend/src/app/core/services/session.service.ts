import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ApprovalHistoryEntry, FileDiff, MigratedFile, MigrationPlan, PauseRecord, Session, SessionFileNode, SessionPage, SessionStatus } from '../models/session.model';

@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.orchestrator}/api/v1/sessions`;

  list(page = 0, size = 20, status?: SessionStatus): Observable<SessionPage> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    return this.http.get<SessionPage>(this.base, { params });
  }

  get(sessionId: string): Observable<Session> {
    return this.http.get<Session>(`${this.base}/${sessionId}`);
  }

  /** Direct lookup of the session driving a given job — avoids the `list(0,1)` race. */
  getByJobId(jobId: string): Observable<Session> {
    return this.http.get<Session>(`${this.base}/by-job/${jobId}`);
  }

  getFiles(sessionId: string): Observable<MigratedFile[]> {
    return this.http.get<MigratedFile[]>(`${this.base}/${sessionId}/files`);
  }

  /** Full project tree (every file in the source ZIP + any CREATED files). */
  getFileTree(sessionId: string): Observable<SessionFileNode[]> {
    return this.http.get<SessionFileNode[]>(`${this.base}/${sessionId}/files/tree`);
  }

  /** #119 — fetch both sides of the diff for a single file. */
  getFileDiff(sessionId: string, path: string): Observable<FileDiff> {
    const params = new HttpParams().set('path', path);
    return this.http.get<FileDiff>(`${this.base}/${sessionId}/files/diff`, { params });
  }

  /** Issue #123 — returns the patch URL the browser can hit directly. */
  patchDownloadUrl(sessionId: string): string {
    return `${this.base}/${sessionId}/files/patch`;
  }

  getPauses(sessionId: string): Observable<PauseRecord[]> {
    return this.http.get<PauseRecord[]>(`${this.base}/${sessionId}/pauses`);
  }

  /** #126 — approval / rejection decisions on this session, newest-first. */
  getApprovalHistory(sessionId: string): Observable<ApprovalHistoryEntry[]> {
    return this.http.get<ApprovalHistoryEntry[]>(`${this.base}/${sessionId}/approval/history`);
  }

  approve(sessionId: string): Observable<Session> {
    return this.http.post<Session>(`${this.base}/${sessionId}/approve`, {});
  }

  /** Issue #10 follow-up — reviewer overrides the AI plan before approving. */
  editPlan(sessionId: string, plan: MigrationPlan): Observable<Session> {
    return this.http.patch<Session>(`${this.base}/${sessionId}/plan`, plan);
  }

  reject(sessionId: string, reason = 'Rejected by reviewer'): Observable<Session> {
    return this.http.post<Session>(`${this.base}/${sessionId}/reject`, { reason });
  }

  pause(sessionId: string): Observable<Session> {
    return this.http.post<Session>(`${this.base}/${sessionId}/pause`, {});
  }

  resume(sessionId: string): Observable<Session> {
    return this.http.post<Session>(`${this.base}/${sessionId}/resume`, {});
  }

  delete(sessionId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${sessionId}`);
  }
}
