import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { OrgSummary } from '../models/report.model';

/**
 * Read-side metrics service (#130).  Wraps the orchestrator's
 * {@code /api/v1/reports/summary} endpoint that #131 added.
 */
@Injectable({ providedIn: 'root' })
export class ReportService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.orchestrator}/api/v1/reports`;

  /** Cross-session aggregate metrics for the dashboard. */
  getOrgSummary(): Observable<OrgSummary> {
    return this.http.get<OrgSummary>(`${this.base}/summary`);
  }
}
