import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { AiCallUsageSummary, TokenUsageSummary } from '../../core/models/billing.model';
import { BillingService } from '../../core/services/billing.service';
import { IconComponent } from '../../shared/icon/icon.component';

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

  readonly totalCost    = computed(() =>
    this.usage()?.reduce((a, r) => a + r.totalCostUsd, 0) ?? null);
  readonly totalInput   = computed(() =>
    this.tokens()?.reduce((a, r) => a + r.inputTokens, 0) ?? null);
  readonly totalOutput  = computed(() =>
    this.tokens()?.reduce((a, r) => a + r.outputTokens, 0) ?? null);
  readonly totalCalls   = computed(() =>
    this.usage()?.reduce((a, r) => a + r.callCount, 0) ?? null);

  constructor() { this.load(); }

  load(): void {
    this.error.set(null);
    this.billingApi.getUsage().subscribe({
      next:  data => this.usage.set(data),
      error: err  => this.error.set(err?.message ?? 'Failed to load usage'),
    });
    this.billingApi.getTokenSummary().subscribe({
      next:  data => this.tokens.set(data),
      error: () => this.tokens.set([]),
    });
  }

  formatK(n: number | null): string {
    if (n === null) return '—';
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(2)}M`;
    if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}K`;
    return n.toString();
  }
}
