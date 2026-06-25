import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { Job, JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';
import { ConfirmDialogService } from '../../shared/confirm-dialog/confirm-dialog.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { FilterOption, SearchFilterBarComponent } from '../../shared/search-filter-bar/search-filter-bar.component';

const STATUS_FILTERS: ReadonlyArray<JobStatus | 'ALL'> = [
  'ALL',
  'PENDING',
  'ANALYZING',
  'MIGRATING',
  'DONE',
  'FAILED',
];

/** Accent dot color per filter — mirrors {@link JobsComponent#statusClass}. */
const FILTER_COLORS: Record<string, string> = {
  ALL:       '#F5C45E',
  PENDING:   '#FFBB33',
  ANALYZING: '#5B8CFF',
  MIGRATING: '#5B8CFF',
  DONE:      '#3DDC97',
  FAILED:    '#FF5C5C',
};

@Component({
  selector: 'app-jobs',
  standalone: true,
  imports: [RouterLink, DatePipe, IconComponent, SearchFilterBarComponent],
  templateUrl: './jobs.component.html',
  styleUrl: './jobs.component.scss',
})
export class JobsComponent {
  private readonly jobsApi = inject(JobService);
  private readonly router = inject(Router);
  private readonly confirmDialog = inject(ConfirmDialogService);

  readonly activeFilter = signal<JobStatus | 'ALL'>('ALL');
  readonly searchQuery = signal('');

  readonly jobs = signal<Job[] | null>(null);

  readonly loadError = signal<string | null>(null);

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

  readonly filterOptions = computed<FilterOption[]>(() => {
    const counts = this.counts();
    return STATUS_FILTERS.map(f => ({
      value: f,
      label: f === 'ALL' ? 'All statuses' : f,
      count: counts[f] ?? 0,
      color: FILTER_COLORS[f],
    }));
  });

  readonly visibleJobs = computed(() => {
    const all = this.jobs();

    if (all === null) {
      return null;
    }

    const filter = this.activeFilter();
    const q = this.searchQuery().trim().toLowerCase();

    return all.filter(job =>
      (filter === 'ALL' || job.status === filter)
      && (!q || job.id.toLowerCase().includes(q) || job.projectId.toLowerCase().includes(q)));
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

  setFilter(filter: string): void {
    this.activeFilter.set(filter as JobStatus | 'ALL');
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