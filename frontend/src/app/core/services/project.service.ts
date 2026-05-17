import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { ConfigFormatPreference, Project } from '../models/project.model';

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.api.project}/api/v1/projects`;

  list(): Observable<Project[]> {
    return this.http.get<Project[]>(this.base);
  }

  get(projectId: string): Observable<Project> {
    return this.http.get<Project>(`${this.base}/${projectId}`);
  }

  upload(file: File, configFormatPreference?: ConfigFormatPreference): Observable<Project> {
    const form = new FormData();
    form.append('file', file);
    if (configFormatPreference) {
      form.append('configFormatPreference', configFormatPreference);
    }
    return this.http.post<Project>(`${this.base}/upload`, form);
  }

  delete(projectId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${projectId}`);
  }
}
