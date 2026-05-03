import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ProviderConfig, SaveProviderConfig } from '../models/provider.model';

@Injectable({ providedIn: 'root' })
export class ProviderService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.orchestrator}/api/ai/providers`;

  list(): Observable<ProviderConfig[]> {
    return this.http.get<ProviderConfig[]>(this.base);
  }

  update(providerId: string, body: SaveProviderConfig): Observable<void> {
    return this.http.put<void>(`${this.base}/${providerId}`, body);
  }
}
