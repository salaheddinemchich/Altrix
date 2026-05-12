import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { GitHubRepo, IngestProjectRequest } from '../models/repository.model';
import { Project } from '../models/project.model';

@Injectable({ providedIn: 'root' })
export class RepositoryService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.orchestrator}/api/v1`;

  listRepos(): Observable<GitHubRepo[]> {
    return this.http.get<GitHubRepo[]>(`${this.base}/repositories`);
  }

  ingestFromGitHub(request: IngestProjectRequest): Observable<Project> {
    return this.http.post<Project>(`${this.base}/projects/from-github`, request);
  }
}
