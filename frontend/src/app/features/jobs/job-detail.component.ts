import { DatePipe } from '@angular/common';
import { Component, computed, effect, inject, input, OnDestroy, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Job, JobStatus } from '../../core/models/job.model';
import { MigrationPlan, Session } from '../../core/models/session.model';
import { JobService } from '../../core/services/job.service';
import { PipelineService } from '../../core/services/pipeline.service';
import { SessionService } from '../../core/services/session.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { PipelineGraphComponent } from '../../shared/pipeline-graph/pipeline-graph.component';
import { SessionTimelineComponent } from '../../shared/session-timeline/session-timeline.component';
import { AuthService } from '../../core/auth/services/auth.service';
import { ConfirmDialogService } from '../../shared/confirm-dialog/confirm-dialog.service';
import { MigrationApplyComponent } from '../migration-apply/migration-apply.component';

const STAGES: JobStatus[] = ['PENDING', 'ANALYZING', 'MIGRATING', 'DONE'];

@Component({
  selector: 'app-job-detail',
  standalone: true,
  imports: [RouterLink, DatePipe, IconComponent, PipelineGraphComponent, SessionTimelineComponent, MigrationApplyComponent],
  templateUrl: './job-detail.component.html',
  styleUrl: './job-detail.component.scss',
})
export class JobDetailComponent implements OnInit, OnDestroy {
  private readonly jobsApi    = inject(JobService);
  private readonly sessionApi = inject(SessionService);
  private readonly pipelineApi = inject(PipelineService);
  private readonly confirmDialog = inject(ConfirmDialogService);
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

  /** WS-fallback polling (10 s) when the socket is unavailable. */
  private pollHandle: ReturnType<typeof setInterval> | null = null;

  /**
   * Always-on lightweight polling (2 s) — re-fetches job + session while the
   * job is non-terminal so the timeline visibly "rolls" stage-to-stage even
   * when the WebSocket connects but emits no live events (which happens when
   * the pipeline started running before the user opened this page).  Stops as
   * soon as the job hits DONE/FAILED so we don't hammer the API forever.
   */
  private livePollHandle: ReturnType<typeof setInterval> | null = null;

  readonly stages = STAGES;

  readonly canDownload = computed(() =>
    this.job()?.status === 'DONE' && !!this.job()?.outputStorageKey);

  readonly savingPlan = signal(false);

  readonly stageIndex = computed(() => {
    const s = this.job()?.status;
    if (!s) return -1;
    if (s === 'FAILED') return -1;
    return STAGES.indexOf(s as JobStatus);
  });

  constructor() {
    // #118 — when the WS gives up, fall back to a 10 s poll until terminal.
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

    // Always-on live polling — runs alongside the WebSocket so the timeline
    // visibly progresses even when no live events arrive (e.g. the pipeline
    // started before the page was opened, or the WS connected after the
    // events fired).  Stops at terminal state to avoid wasted API calls.
    effect(() => {
      const status = this.job()?.status;
      const terminal = status === 'DONE' || status === 'FAILED';
      if (!terminal && !this.livePollHandle) {
        this.startLivePolling();
      } else if (terminal && this.livePollHandle) {
        this.stopLivePolling();
      }
    });
  }

  ngOnInit(): void { this.load(); }

  ngOnDestroy(): void {
    this.stopPolling();
    this.stopLivePolling();
  }

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

  /**
   * Lightweight live polling — refreshes job + session every 2 seconds so the
   * timeline visibly rolls between stages even without WebSocket traffic.
   * Stops at terminal state.
   */
  private startLivePolling(): void {
    this.livePollHandle = setInterval(() => {
      // Refresh both job and session; session.status is the granular signal
      // the timeline backfill keys off.
      this.jobsApi.get(this.id()).subscribe({
        next: job => this.job.set(job),
        error: () => {},
      });
      this.sessionApi.getByJobId(this.id()).subscribe({
        next: s => this.session.set(s),
        error: () => {},
      });
    }, 2_000);
  }

  private stopLivePolling(): void {
    if (this.livePollHandle) {
      clearInterval(this.livePollHandle);
      this.livePollHandle = null;
    }
  }

  load(): void {
    this.loadError.set(null);
    this.jobsApi.get(this.id()).subscribe({
      next: job => {
        this.job.set(job);
        this.loadSession();
      },
      error: err => this.loadError.set(err?.message ?? 'Request failed'),
    });
  }

  private loadSession(): void {
    // Direct by-job lookup — used to be `list(0,1)` + find-by-jobId, which
    // returned the WRONG session whenever any newer job existed.
    this.sessionApi.getByJobId(this.id()).subscribe({
      next: s => this.session.set(s),
      error: () => {},
    });
  }

  async approve(): Promise<void> {
    const s = this.session();
    if (!s) return;
    // #125 — identity confirmation step.  Surfaces the @login the decision
    // will be recorded against so a reviewer can't be tricked into approving
    // from a session that has silently swapped users underneath them.
    const login = this.auth.user()?.login ?? this.auth.userId() ?? 'this account';
    const ok = await this.confirmDialog.ask(`Approving as @${login} — proceed?`);
    if (!ok) return;
    this.sessionApi.approve(s.sessionId).subscribe({
      next: updated => this.session.set(updated),
      error: () => {},
    });
  }

  async reject(): Promise<void> {
    const s = this.session();
    if (!s) return;
    const login = this.auth.user()?.login ?? this.auth.userId() ?? 'this account';
    const ok = await this.confirmDialog.ask({
      message: `Rejecting as @${login} — proceed?`,
      danger: true,
    });
    if (!ok) return;
    this.sessionApi.reject(s.sessionId).subscribe({
      next: updated => this.session.set(updated),
      error: () => {},
    });
  }

  savePlan(edited: MigrationPlan): void {
    const s = this.session();
    if (!s) return;
    this.savingPlan.set(true);
    this.sessionApi.editPlan(s.sessionId, edited).subscribe({
      next: updated => { this.session.set(updated); this.savingPlan.set(false); },
      error: () => this.savingPlan.set(false),
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
