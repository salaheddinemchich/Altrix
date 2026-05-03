import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { Job, JobStatus, JobStatusResponse } from '../models/job.model';

@Injectable({ providedIn: 'root' })
export class JobService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.job}/api/v1/jobs`;

  list(status?: JobStatus): Observable<Job[]> {
    let params = new HttpParams();
    if (status) params = params.set('status', status);
    return this.http.get<Job[]>(this.base, { params });
  }

  get(jobId: string): Observable<Job> {
    return this.http.get<Job>(`${this.base}/${jobId}`);
  }

  getStatus(jobId: string): Observable<JobStatusResponse> {
    return this.http.get<JobStatusResponse>(`${this.base}/${jobId}/status`);
  }

  /** URL the browser can navigate to so the migrated ZIP downloads. */
  downloadUrl(jobId: string): string {
    return `${this.base}/${jobId}/download`;
  }
}
