import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Job, JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';

const STATUS_FILTERS: ReadonlyArray<JobStatus | 'ALL'> =
  ['ALL', 'PENDING', 'ANALYZING', 'MIGRATING', 'DONE', 'FAILED'];

@Component({
  selector: 'app-jobs',
  standalone: true,
  imports: [RouterLink, DatePipe],
  templateUrl: './jobs.component.html',
  styleUrl: './jobs.component.scss'
})
export class JobsComponent {
  private readonly jobsApi = inject(JobService);

  readonly filters = STATUS_FILTERS;
  readonly activeFilter = signal<JobStatus | 'ALL'>('ALL');

  readonly jobs       = signal<Job[] | null>(null);
  readonly loadError  = signal<string | null>(null);

  readonly visibleJobs = computed(() => {
    const all = this.jobs();
    if (all === null) return null;
    const f = this.activeFilter();
    return f === 'ALL' ? all : all.filter(j => j.status === f);
  });

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.jobs.set(null);
    this.jobsApi.list().subscribe({
      next: list => this.jobs.set(list),
      error: err => {
        this.jobs.set([]);
        this.loadError.set(err?.message ?? 'Request failed');
      }
    });
  }

  setFilter(filter: JobStatus | 'ALL'): void {
    this.activeFilter.set(filter);
  }

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
