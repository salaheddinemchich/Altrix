import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../core/auth/services/auth.service';
import { JobStatus } from '../../core/models/job.model';
import { OrgSummary } from '../../core/models/report.model';
import { JobService } from '../../core/services/job.service';
import { ProjectService } from '../../core/services/project.service';
import { ProviderService } from '../../core/services/provider.service';
import { ReportService } from '../../core/services/report.service';
import { SessionService } from '../../core/services/session.service';
import { IconComponent } from '../../shared/icon/icon.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, PercentPipe, IconComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  protected readonly auth     = inject(AuthService);
  private  readonly projects  = inject(ProjectService);
  private  readonly jobs      = inject(JobService);
  private  readonly providers = inject(ProviderService);
  private  readonly sessions  = inject(SessionService);
  private  readonly reports   = inject(ReportService);

  readonly projectList  = toSignal(this.projects.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly jobList      = toSignal(this.jobs.list().pipe(catchError(() => of([]))),       { initialValue: null });
  readonly providerList = toSignal(this.providers.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly sessionPage  = toSignal(this.sessions.list(0, 1).pipe(catchError(() => of(null))), { initialValue: null });
  /** #130 — cross-session aggregate metrics for the insights section. */
  readonly orgSummary   = toSignal<OrgSummary | null>(
    this.reports.getOrgSummary().pipe(catchError(() => of(null))), { initialValue: null });

  readonly projectCount  = computed(() => this.projectList()?.length ?? null);
  readonly jobCount      = computed(() => this.jobList()?.length ?? null);
  readonly enabledProvs  = computed(() => this.providerList()?.filter(p => p.effectiveEnabled).length ?? null);
  readonly sessionCount  = computed(() => this.sessionPage()?.totalElements ?? null);

  readonly recentJobs    = computed(() => (this.jobList() ?? []).slice(0, 6));
  readonly runningJobs   = computed(() =>
    (this.jobList() ?? []).filter(j => j.status === 'ANALYZING' || j.status === 'MIGRATING').length);

  /**
   * Sorted [status, count] pairs for the per-status horizontal bar chart
   * in the insights section.  Status keys with zero count are dropped so
   * the chart focuses on the categories that actually have rows.
   */
  readonly statusBars = computed(() => {
    const counts = this.orgSummary()?.countsByStatus ?? {};
    return Object.entries(counts)
      .filter(([, v]) => v > 0)
      .sort((a, b) => b[1] - a[1])
      .map(([status, count]) => {
        const max = Math.max(...Object.values(counts), 1);
        return { status, count, percent: Math.round((count / max) * 100) };
      });
  });

  /** Human-readable duration label: "1m 23s", "12s", "—" for n=0. */
  readonly averageDurationLabel = computed(() => {
    const s = this.orgSummary()?.averageDurationSeconds ?? 0;
    if (s <= 0) return '—';
    const sec = Math.round(s);
    if (sec < 60) return `${sec}s`;
    return `${Math.floor(sec / 60)}m ${(sec % 60).toString().padStart(2, '0')}s`;
  });

  statusBarClass(status: string): string {
    switch (status) {
      case 'DONE':              return 'bar-done';
      case 'FAILED':            return 'bar-failed';
      case 'PAUSED':            return 'bar-paused';
      case 'AWAITING_APPROVAL':
      case 'PLAN_READY':        return 'bar-waiting';
      case 'MIGRATING':
      case 'VALIDATING':
      case 'CONTEXT_ANALYSED':
      case 'PENDING':           return 'bar-active';
      default:                  return '';
    }
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
