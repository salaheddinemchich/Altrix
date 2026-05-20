import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { AiCallUsageSummary, TokenUsageSummary } from '../../core/models/billing.model';
import { BillingService } from '../../core/services/billing.service';
import { IconComponent } from '../../shared/icon/icon.component';

type Period = 'mtd' | '7d' | '30d' | '90d';

@Component({
  selector: 'app-billing',
  standalone: true,
  imports: [DatePipe, DecimalPipe, IconComponent],
  templateUrl: './billing.component.html',
  styleUrl: './billing.component.scss',
})
export class BillingComponent {
  private readonly billingApi = inject(BillingService);

  readonly usage    = signal<AiCallUsageSummary[] | null>(null);
  readonly tokens   = signal<TokenUsageSummary[] | null>(null);
  readonly error    = signal<string | null>(null);

  /** #153 — time window selector.  'mtd' = month-to-date (backend default). */
  readonly period   = signal<Period>('mtd');
  readonly periods: ReadonlyArray<{ key: Period; label: string }> = [
    { key: 'mtd', label: 'Month to date' },
    { key: '7d',  label: 'Last 7 days' },
    { key: '30d', label: 'Last 30 days' },
    { key: '90d', label: 'Last 90 days' },
  ];

  readonly totalCost    = computed(() =>
    this.usage()?.reduce((a, r) => a + r.totalCostUsd, 0) ?? null);
  readonly totalInput   = computed(() =>
    this.tokens()?.reduce((a, r) => a + r.inputTokens, 0) ?? null);
  readonly totalOutput  = computed(() =>
    this.tokens()?.reduce((a, r) => a + r.outputTokens, 0) ?? null);
  readonly totalCalls   = computed(() =>
    this.usage()?.reduce((a, r) => a + r.callCount, 0) ?? null);

  constructor() {
    // #153 — reload the usage table whenever the period chip changes.  The
    // token-summary endpoint has no time filter so it loads once.
    effect(() => {
      const p = this.period();
      this.loadUsageForPeriod(p);
    });
    this.loadTokenSummaryOnce();
  }

  setPeriod(p: Period): void { this.period.set(p); }

  /** #153 — download the current usage table as CSV. */
  exportCsv(): void {
    const rows = this.usage() ?? [];
    const header = 'agent,provider,callCount,inputTokens,outputTokens,totalCostUsd';
    const body = rows.map(r => [
      csvEscape(r.agentName),
      csvEscape(r.providerName),
      r.callCount,
      r.totalInputTokens,
      r.totalOutputTokens,
      r.totalCostUsd.toFixed(6),
    ].join(','));
    downloadCsv(
      `altrix-billing-${this.period()}-${new Date().toISOString().slice(0, 10)}.csv`,
      [header, ...body].join('\n')
    );
  }

  formatK(n: number | null): string {
    if (n === null) return '—';
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
    if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}K`;
    return n.toString();
  }

  // ── internals ────────────────────────────────────────────────────────────
  private loadUsageForPeriod(p: Period): void {
    this.error.set(null);
    this.usage.set(null);
    const range = toIsoRange(p);
    this.billingApi.getUsage(range.from, range.to).subscribe({
      next:  data => this.usage.set(data ?? []),
      error: err  => this.error.set(err?.message ?? 'Failed to load usage'),
    });
  }

  private loadTokenSummaryOnce(): void {
    this.billingApi.getTokenSummary().subscribe({
      next:  data => this.tokens.set(data),
      error: () => this.tokens.set([]),
    });
  }
}

// ── helpers ────────────────────────────────────────────────────────────────

/** {from, to} ISO strings for the selected period.  'mtd' returns empty so
 *  the backend default (month-to-date in UTC) applies. */
function toIsoRange(p: Period): { from?: string; to?: string } {
  if (p === 'mtd') return {};
  const now = new Date();
  const days = p === '7d' ? 7 : (p === '30d' ? 30 : 90);
  const from = new Date(now.getTime() - days * 24 * 60 * 60 * 1000);
  return { from: from.toISOString(), to: now.toISOString() };
}

function csvEscape(value: string): string {
  const s = String(value ?? '');
  if (s.includes(',') || s.includes('"') || s.includes('\n')) {
    return '"' + s.replace(/"/g, '""') + '"';
  }
  return s;
}

function downloadCsv(filename: string, content: string): void {
  const blob = new Blob([content], { type: 'text/csv;charset=utf-8' });
  const url  = URL.createObjectURL(blob);
  const a    = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
