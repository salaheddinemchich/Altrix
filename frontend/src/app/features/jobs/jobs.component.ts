import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Job, JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';
import { ConfirmDialogService } from '../../shared/confirm-dialog/confirm-dialog.service';
import { IconComponent } from '../../shared/icon/icon.component';

const STATUS_FILTERS: ReadonlyArray<JobStatus | 'ALL'> = [
  'ALL',
  'PENDING',
  'ANALYZING',
  'MIGRATING',
  'DONE',
  'FAILED',
];

/** Accent + soft background per filter chip — mirrors {@link JobsComponent#statusClass}. */
const FILTER_COLORS: Record<string, { color: string; bg: string }> = {
  ALL:       { color: '#F5C45E', bg: 'rgba(245,196,94,0.14)' },
  PENDING:   { color: '#FFBB33', bg: 'rgba(255,187,51,0.14)' },
  ANALYZING: { color: '#5B8CFF', bg: 'rgba(91,140,255,0.14)' },
  MIGRATING: { color: '#5B8CFF', bg: 'rgba(91,140,255,0.14)' },
  DONE:      { color: '#3DDC97', bg: 'rgba(61,220,151,0.14)' },
  FAILED:    { color: '#FF5C5C', bg: 'rgba(255,92,92,0.14)' },
};

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
  private readonly confirmDialog = inject(ConfirmDialogService);

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

  filterColor(f: JobStatus | 'ALL'): string {
    return FILTER_COLORS[f]?.color ?? '#F5C45E';
  }

  filterColorBg(f: JobStatus | 'ALL'): string {
    return FILTER_COLORS[f]?.bg ?? 'rgba(245,196,94,0.14)';
  }

  goToJob(id: string): void {
    this.router.navigate(['/jobs', id]);
  }

  async deleteJob(j: Job, ev: Event): Promise<void> {
    ev.stopPropagation();
    const ok = await this.confirmDialog.ask({
      message: `Delete job ${j.id.substring(0, 8)}…? This cannot be undone.`,
      danger: true,
    });
    if (!ok) return;
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