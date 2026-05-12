import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AiCallUsageSummary, TokenUsageSummary } from '../models/billing.model';

@Injectable({ providedIn: 'root' })
export class BillingService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.orchestrator}/api/v1/billing`;
  private readonly tokenBase = `${environment.api.orchestrator}/api/ai/token-usage`;

  getUsage(from?: string, to?: string): Observable<AiCallUsageSummary[]> {
    let params = new HttpParams();
    if (from) params = params.set('from', from);
    if (to) params = params.set('to', to);
    return this.http.get<AiCallUsageSummary[]>(`${this.base}/usage`, { params });
  }

  getTokenSummary(): Observable<TokenUsageSummary[]> {
    return this.http.get<TokenUsageSummary[]>(this.tokenBase);
  }
}
