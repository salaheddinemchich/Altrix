import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, OnDestroy, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Job, JobStatus } from '../../core/models/job.model';
import { Session } from '../../core/models/session.model';
import { JobService } from '../../core/services/job.service';
import { PipelineService } from '../../core/services/pipeline.service';
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
export class JobDetailComponent implements OnInit, OnDestroy {
  private readonly jobsApi    = inject(JobService);
  private readonly sessionApi = inject(SessionService);
  private readonly pipelineApi = inject(PipelineService);
  protected readonly auth     = inject(AuthService);

  readonly id = input.required<string>();

  readonly job       = signal<Job | null>(null);
  readonly loadError = signal<string | null>(null);
  readonly session   = signal<Session | null>(null);
  /** Hide the polling banner when the user dismisses it (#118). */
  readonly bannerDismissed = signal<boolean>(false);

  /** Issue #118 — exposed to the template so a banner can react to it. */
  readonly wsState = this.pipelineApi.connectionState;
  readonly pollingActive = computed(() =>
    this.wsState() === 'unavailable' && !this.bannerDismissed());

  /** Active polling handle so we can clear on destroy / terminal state. */
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  readonly stages = STAGES;

  readonly canDownload = computed(() =>
    this.job()?.status === 'DONE' && !!this.job()?.outputStorageKey);

  readonly stageIndex = computed(() => {
    const s = this.job()?.status;
    if (!s) return -1;
    if (s === 'FAILED') return -1;
    return STAGES.indexOf(s as JobStatus);
  });

  constructor() {
    // #118 — when the WS gives up, start polling the job every 10s until it
    // reaches a terminal state.  The effect re-evaluates whenever wsState
    // flips, so a reconnect (via retryConnection) cleanly stops polling.
    effect(() => {
      const state = this.wsState();
      const status = this.job()?.status;
      const terminal = status === 'DONE' || status === 'FAILED';

      if (state === 'unavailable' && !terminal && !this.pollHandle) {
        this.startPolling();
      } else if ((state === 'connected' || terminal) && this.pollHandle) {
        this.stopPolling();
      }
    });
  }

  ngOnInit(): void { this.load(); }

  ngOnDestroy(): void { this.stopPolling(); }

  dismissBanner(): void { this.bannerDismissed.set(true); }

  retryWebSocket(): void {
    this.bannerDismissed.set(false);
    this.pipelineApi.retryConnection();
  }

  private startPolling(): void {
    // 10 second cadence per #118 acceptance criteria
    this.pollHandle = setInterval(() => this.load(), 10_000);
  }

  private stopPolling(): void {
    if (this.pollHandle) {
      clearInterval(this.pollHandle);
      this.pollHandle = null;
    }
  }

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
