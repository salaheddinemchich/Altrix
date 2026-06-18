import { DatePipe, DecimalPipe, PercentPipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { ChartConfiguration, ChartOptions } from 'chart.js';
import { BaseChartDirective } from 'ng2-charts';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../core/auth/services/auth.service';
import { AiCallUsageSummary } from '../../core/models/billing.model';
import { JobStatus } from '../../core/models/job.model';
import { OrgSummary } from '../../core/models/report.model';
import { BillingService } from '../../core/services/billing.service';
import { JobService } from '../../core/services/job.service';
import { ProjectService } from '../../core/services/project.service';
import { ProviderService } from '../../core/services/provider.service';
import { ReportService } from '../../core/services/report.service';
import { SessionService } from '../../core/services/session.service';
import { ThemeService } from '../../core/services/theme.service';
import { IconComponent } from '../../shared/icon/icon.component';

/** Brand accent per session status — used for the donut chart + its legend dots. */
const STATUS_COLORS: Record<string, string> = {
  DONE: '#3DDC97',
  FAILED: '#FF5C5C',
  PAUSED: '#FFBB33',
  AWAITING_APPROVAL: '#F5C45E',
  PLAN_READY: '#F5C45E',
  MIGRATING: '#5B8CFF',
  VALIDATING: '#5B8CFF',
  CONTEXT_ANALYSED: '#5B8CFF',
  PENDING: '#5B8CFF',
};
const STATUS_COLOR_DEFAULT = '#8B93A7';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, DatePipe, DecimalPipe, PercentPipe, IconComponent, BaseChartDirective],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  protected readonly auth      = inject(AuthService);
  private  readonly projects   = inject(ProjectService);
  private  readonly jobs       = inject(JobService);
  private  readonly providers  = inject(ProviderService);
  private  readonly sessions   = inject(SessionService);
  private  readonly reports    = inject(ReportService);
  private  readonly billing    = inject(BillingService);
  private  readonly themeSvc   = inject(ThemeService);

  readonly projectList  = toSignal(this.projects.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly jobList      = toSignal(this.jobs.list().pipe(catchError(() => of([]))),       { initialValue: null });
  readonly providerList = toSignal(this.providers.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly sessionPage  = toSignal(this.sessions.list(0, 1).pipe(catchError(() => of(null))), { initialValue: null });
  /** #130 — cross-session aggregate metrics for the insights section. */
  readonly orgSummary   = toSignal<OrgSummary | null>(
    this.reports.getOrgSummary().pipe(catchError(() => of(null))), { initialValue: null });

  /** Admin-only — mirrors BillingComponent's guard so non-admins never hit a 403. */
  readonly canViewCost = this.auth.isAdmin;
  readonly costUsage = toSignal<AiCallUsageSummary[] | null>(
    this.canViewCost() ? this.billing.getUsage().pipe(catchError(() => of([]))) : of(null),
    { initialValue: null });

  readonly projectCount  = computed(() => this.projectList()?.length ?? null);
  readonly jobCount      = computed(() => this.jobList()?.length ?? null);
  readonly enabledProvs  = computed(() => this.providerList()?.filter(p => p.effectiveEnabled).length ?? null);
  readonly sessionCount  = computed(() => this.sessionPage()?.totalElements ?? null);

  readonly recentJobs    = computed(() => (this.jobList() ?? []).slice(0, 6));
  readonly runningJobs   = computed(() =>
    (this.jobList() ?? []).filter(j => j.status === 'ANALYZING' || j.status === 'MIGRATING').length);

  /**
   * Sorted [status, count] pairs driving both the donut chart and its
   * legend list in the insights section.  Zero-count statuses are dropped.
   */
  readonly statusBars = computed(() => {
    const counts = this.orgSummary()?.countsByStatus ?? {};
    const total = Object.values(counts).reduce((a, b) => a + b, 0) || 1;
    return Object.entries(counts)
      .filter(([, v]) => v > 0)
      .sort((a, b) => b[1] - a[1])
      .map(([status, count]) => ({ status, count, percent: Math.round((count / total) * 100) }));
  });

  /** Human-readable duration label: "1m 23s", "12s", "—" for n=0. */
  readonly averageDurationLabel = computed(() => {
    const s = this.orgSummary()?.averageDurationSeconds ?? 0;
    if (s <= 0) return '—';
    const sec = Math.round(s);
    if (sec < 60) return `${sec}s`;
    return `${Math.floor(sec / 60)}m ${(sec % 60).toString().padStart(2, '0')}s`;
  });

  // ── Theme-aware chart styling ─────────────────────────────────────────────
  private readonly isDark       = computed(() => this.themeSvc.theme() === 'dark');
  private readonly chartText    = computed(() => this.isDark() ? '#8B93A7' : '#5B6478');
  private readonly chartGrid    = computed(() => this.isDark() ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.07)');
  private readonly chartSurface = computed(() => this.isDark() ? '#131720' : '#FFFFFF');

  // ── Sessions-by-status donut ────────────────────────────────────────────
  readonly statusDoughnutData = computed<ChartConfiguration<'doughnut'>['data']>(() => {
    const bars = this.statusBars();
    return {
      labels: bars.map(b => b.status),
      datasets: [{
        data: bars.map(b => b.count),
        backgroundColor: bars.map(b => STATUS_COLORS[b.status] ?? STATUS_COLOR_DEFAULT),
        borderColor: this.chartSurface(),
        borderWidth: 2,
        hoverOffset: 4,
      }],
    };
  });

  readonly statusDoughnutOptions = computed<ChartOptions<'doughnut'>>(() => ({
    responsive: true,
    maintainAspectRatio: false,
    cutout: '72%',
    plugins: {
      legend: { display: false },
      tooltip: {
        backgroundColor: this.chartSurface(),
        titleColor: this.chartText(),
        bodyColor: this.chartText(),
        borderColor: this.chartGrid(),
        borderWidth: 1,
        padding: 10,
      },
    },
  }));

  /** Per-day job-creation counts for the last 14 days — real data derived from {@link jobList}. */
  readonly jobsByDay = computed(() => {
    const jobs = this.jobList() ?? [];
    const days: { label: string; key: string; count: number }[] = [];
    const now = new Date();
    for (let i = 13; i >= 0; i--) {
      const d = new Date(now);
      d.setDate(now.getDate() - i);
      days.push({
        label: d.toLocaleDateString(undefined, { month: 'short', day: 'numeric' }),
        key: d.toISOString().slice(0, 10),
        count: 0,
      });
    }
    const byKey = new Map(days.map(d => [d.key, d]));
    for (const j of jobs) {
      const key = new Date(j.createdAt).toISOString().slice(0, 10);
      const bucket = byKey.get(key);
      if (bucket) bucket.count++;
    }
    return days;
  });

  readonly jobsBarData = computed<ChartConfiguration<'bar'>['data']>(() => ({
    labels: this.jobsByDay().map(d => d.label),
    datasets: [{
      data: this.jobsByDay().map(d => d.count),
      backgroundColor: 'rgba(91,140,255,0.55)',
      hoverBackgroundColor: '#5B8CFF',
      borderRadius: 4,
      maxBarThickness: 18,
    }],
  }));

  readonly jobsBarOptions = computed<ChartOptions<'bar'>>(() => ({
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: {
      x: { ticks: { color: this.chartText(), font: { size: 10 } }, grid: { display: false } },
      y: {
        beginAtZero: true,
        ticks: { color: this.chartText(), font: { size: 10 }, precision: 0 },
        grid: { color: this.chartGrid() },
      },
    },
  }));

  // ── Cost & Usage (admin-only) ───────────────────────────────────────────
  readonly totalCostMtd   = computed(() => this.costUsage()?.reduce((a, r) => a + r.totalCostUsd, 0) ?? null);
  readonly totalCallsMtd  = computed(() => this.costUsage()?.reduce((a, r) => a + r.callCount, 0) ?? null);
  readonly totalTokensMtd = computed(() =>
    this.costUsage()?.reduce((a, r) => a + r.totalInputTokens + r.totalOutputTokens, 0) ?? null);

  readonly costByProvider = computed(() => {
    const rows = this.costUsage() ?? [];
    const byProvider = new Map<string, number>();
    for (const r of rows) byProvider.set(r.providerName, (byProvider.get(r.providerName) ?? 0) + r.totalCostUsd);
    return [...byProvider.entries()]
      .map(([provider, cost]) => ({ provider, cost }))
      .sort((a, b) => b.cost - a.cost);
  });

  readonly costBarData = computed<ChartConfiguration<'bar'>['data']>(() => ({
    labels: this.costByProvider().map(c => c.provider),
    datasets: [{
      data: this.costByProvider().map(c => c.cost),
      backgroundColor: '#F5C45E',
      borderRadius: 4,
      maxBarThickness: 22,
    }],
  }));

  readonly costBarOptions = computed<ChartOptions<'bar'>>(() => ({
    indexAxis: 'y' as const,
    responsive: true,
    maintainAspectRatio: false,
    plugins: { legend: { display: false } },
    scales: {
      x: {
        beginAtZero: true,
        ticks: { color: this.chartText(), font: { size: 10 }, callback: v => `$${v}` },
        grid: { color: this.chartGrid() },
      },
      y: { ticks: { color: this.chartText(), font: { size: 10 } }, grid: { display: false } },
    },
  }));

  formatCost(n: number | null): string {
    return n === null ? '—' : `$${n.toFixed(2)}`;
  }

  formatK(n: number | null): string {
    if (n === null) return '—';
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
    if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}K`;
    return n.toString();
  }

  statusDotColor(status: string): string {
    return STATUS_COLORS[status] ?? STATUS_COLOR_DEFAULT;
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
