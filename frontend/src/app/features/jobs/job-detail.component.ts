import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Job, JobStatus } from '../../core/models/job.model';
import { Session } from '../../core/models/session.model';
import { JobService } from '../../core/services/job.service';
import { SessionService } from '../../core/services/session.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { PipelineGraphComponent } from '../../shared/pipeline-graph/pipeline-graph.component';
import { SessionTimelineComponent } from '../../shared/session-timeline/session-timeline.component';
import { AuthService } from '../../core/auth/services/auth.service';

const STAGES: JobStatus[] = ['PENDING', 'ANALYZING', 'MIGRATING', 'DONE'];

@Component({
  selector: 'app-job-detail',
  standalone: true,
  imports: [RouterLink, DatePipe, IconComponent, PipelineGraphComponent, SessionTimelineComponent],
  templateUrl: './job-detail.component.html',
  styleUrl: './job-detail.component.scss',
})
export class JobDetailComponent implements OnInit {
  private readonly jobsApi    = inject(JobService);
  private readonly sessionApi = inject(SessionService);
  protected readonly auth     = inject(AuthService);

  readonly id = input.required<string>();

  readonly job       = signal<Job | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly session   = signal<Session | null>(null);

  readonly stages = STAGES;

  readonly canDownload = computed(() =>
    this.job()?.status === 'DONE' && !!this.job()?.outputStorageKey);

  readonly stageIndex = computed(() => {
    const s = this.job()?.status;
    if (!s) return -1;
    if (s === 'FAILED') return -1;
    return STAGES.indexOf(s as JobStatus);
  });

  ngOnInit(): void { this.load(); }

  load(): void {
    this.loadError.set(null);
    this.jobsApi.get(this.id()).subscribe({
      next: job => {
        this.job.set(job);
        this.loadSession(job.projectId);
      },
      error: err => this.loadError.set(err?.message ?? 'Request failed'),
    });
  }

  private loadSession(projectId: string): void {
    this.sessionApi.list(0, 1).subscribe({
      next: page => {
        const found = page.content.find(s => s.jobId === this.id() || s.projectId === projectId);
        if (found) this.session.set(found);
      },
      error: () => {},
    });
  }

  approve(): void {
    const s = this.session();
    if (!s) return;
    this.sessionApi.approve(s.sessionId).subscribe({
      next: updated => this.session.set(updated),
      error: () => {},
    });
  }

  reject(): void {
    const s = this.session();
    if (!s) return;
    this.sessionApi.reject(s.sessionId).subscribe({
      next: updated => this.session.set(updated),
      error: () => {},
    });
  }

  pauseSession(): void {
    const s = this.session();
    if (!s) return;
    this.sessionApi.pause(s.sessionId).subscribe({
      next: updated => this.session.set(updated),
      error: () => {},
    });
  }

  resumeSession(): void {
    const s = this.session();
    if (!s) return;
    this.sessionApi.resume(s.sessionId).subscribe({
      next: updated => this.session.set(updated),
      error: () => {},
    });
  }

  downloadUrl(): string { return this.jobsApi.downloadUrl(this.id()); }

  statusClass(status: JobStatus | string): string {
    switch (status) {
      case 'DONE':      return 'success';
      case 'FAILED':    return 'danger';
      case 'ANALYZING':
      case 'MIGRATING': return 'info';
      case 'PENDING':   return 'warning';
      default:          return '';
    }
  }

  sessionStatusClass(status: string): string {
    switch (status) {
      case 'DONE':              return 'success';
      case 'FAILED':            return 'danger';
      case 'AWAITING_APPROVAL': return 'warning';
      case 'PAUSED':            return 'warning';
      case 'MIGRATING':
      case 'VALIDATING':        return 'info';
      default:                  return '';
    }
  }
}
