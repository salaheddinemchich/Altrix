import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Job, JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';
import { IconComponent } from '../../shared/icon/icon.component';

const STATUS_FILTERS: ReadonlyArray<JobStatus | 'ALL'> = [
  'ALL',
  'PENDING',
  'ANALYZING',
  'MIGRATING',
  'DONE',
  'FAILED',
];

@Component({
  selector: 'app-jobs',
  standalone: true,
  imports: [RouterLink, DatePipe, IconComponent],
  templateUrl: './jobs.component.html',
  styleUrl: './jobs.component.scss',
})
export class JobsComponent {
  private readonly jobsApi = inject(JobService);
  private readonly router = inject(Router);

  readonly filters = STATUS_FILTERS;

  readonly activeFilter = signal<JobStatus | 'ALL'>('ALL');

  readonly jobs = signal<Job[] | null>(null);

  readonly loadError = signal<string | null>(null);

  readonly visibleJobs = computed(() => {
    const all = this.jobs();

    if (all === null) {
      return null;
    }

    const filter = this.activeFilter();

    return filter === 'ALL'
      ? all
      : all.filter(job => job.status === filter);
  });

  readonly counts = computed(() => {
    const all = this.jobs() ?? [];

    const result: Record<string, number> = {
      ALL: all.length,
    };

    for (const filter of STATUS_FILTERS) {
      if (filter !== 'ALL') {
        result[filter] = all.filter(job => job.status === filter).length;
      }
    }

    return result;
  });

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.jobs.set(null);

    this.jobsApi.list().subscribe({
      next: list => {
        this.jobs.set(list);
      },

      error: err => {
        this.jobs.set([]);
        this.loadError.set(err?.message ?? 'Request failed');
      },
    });
  }

  setFilter(filter: JobStatus | 'ALL'): void {
    this.activeFilter.set(filter);
  }

  goToJob(id: string): void {
    this.router.navigate(['/jobs', id]);
  }

  deleteJob(j: Job, ev: Event): void {
    ev.stopPropagation();
    if (!confirm(`Delete job ${j.id.substring(0, 8)}…? This cannot be undone.`)) return;
    this.jobsApi.delete(j.id).subscribe({
      next: () => this.refresh(),
      error: err => alert('Delete failed: ' + (err?.message ?? 'unknown')),
    });
  }

  statusClass(status: JobStatus): string {
    switch (status) {
      case 'DONE':
        return 'success';

      case 'FAILED':
        return 'danger';

      case 'ANALYZING':
      case 'MIGRATING':
        return 'info';

      case 'PENDING':
        return 'warning';

      default:
        return '';
    }
  }
}