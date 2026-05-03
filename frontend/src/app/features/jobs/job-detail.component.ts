import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Job, JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';

@Component({
  selector: 'app-job-detail',
  standalone: true,
  imports: [RouterLink, DatePipe],
  templateUrl: './job-detail.component.html',
  styleUrl: './job-detail.component.scss'
})
export class JobDetailComponent implements OnInit {
  private readonly jobsApi = inject(JobService);

  /** Bound from the route via {@code withComponentInputBinding()}. */
  readonly id = input.required<string>();

  readonly job        = signal<Job | null>(null);
  readonly loadError  = signal<string | null>(null);

  readonly canDownload = computed(() =>
    this.job()?.status === 'DONE' && !!this.job()?.outputStorageKey);

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.jobsApi.get(this.id()).subscribe({
      next: job => this.job.set(job),
      error: err => this.loadError.set(err?.message ?? 'Request failed')
    });
  }

  downloadUrl(): string {
    return this.jobsApi.downloadUrl(this.id());
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
