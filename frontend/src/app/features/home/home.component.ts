import { DatePipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';

import { JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';
import { ProjectService } from '../../core/services/project.service';
import { ProviderService } from '../../core/services/provider.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, DatePipe],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss'
})
export class HomeComponent {
  private readonly projects  = inject(ProjectService);
  private readonly jobs      = inject(JobService);
  private readonly providers = inject(ProviderService);

  /** Each value is `null` until the request resolves; on error it falls back to []. */
  readonly projectList  = toSignal(this.projects.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly jobList      = toSignal(this.jobs.list().pipe(catchError(() => of([]))),       { initialValue: null });
  readonly providerList = toSignal(this.providers.list().pipe(catchError(() => of([]))),  { initialValue: null });

  readonly projectCount = computed(() => this.projectList()?.length ?? null);
  readonly jobCount     = computed(() => this.jobList()?.length ?? null);
  readonly enabledProvs = computed(() =>
    this.providerList()?.filter(p => p.effectiveEnabled).length ?? null);

  readonly recentJobs = computed(() => (this.jobList() ?? []).slice(0, 5));

  statusClass(status: JobStatus): string {
    switch (status) {
      case 'DONE':      return 'success';
      case 'FAILED':    return 'danger';
      case 'ANALYZING':
      case 'MIGRATING': return 'info';
      case 'PENDING':   return 'warning';
      default:          return '';
    }
  }
}
