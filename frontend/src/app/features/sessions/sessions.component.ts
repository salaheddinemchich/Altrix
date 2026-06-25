import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/services/auth.service';
import { Session, SessionStatus } from '../../core/models/session.model';
import { SessionService } from '../../core/services/session.service';
import { ConfirmDialogService } from '../../shared/confirm-dialog/confirm-dialog.service';
import { IconComponent } from '../../shared/icon/icon.component';
import { FilterOption, SearchFilterBarComponent } from '../../shared/search-filter-bar/search-filter-bar.component';

const STATUS_FILTERS: ReadonlyArray<SessionStatus | 'ALL'> = [
  'ALL', 'PENDING', 'CONTEXT_ANALYSED', 'PLAN_READY',
  'AWAITING_APPROVAL', 'MIGRATING', 'VALIDATING', 'DONE', 'FAILED', 'PAUSED',
];

/** Accent dot color per filter — mirrors {@link SessionsComponent#statusClass}. */
const FILTER_COLORS: Record<string, string> = {
  ALL:               '#F5C45E',
  PENDING:           '#FFBB33',
  CONTEXT_ANALYSED:  '#5B8CFF',
  PLAN_READY:        '#5B8CFF',
  AWAITING_APPROVAL: '#FFBB33',
  MIGRATING:         '#5B8CFF',
  VALIDATING:        '#5B8CFF',
  DONE:              '#3DDC97',
  FAILED:            '#FF5C5C',
  PAUSED:            '#FFBB33',
};

@Component({
  selector: 'app-sessions',
  standalone: true,
  imports: [DatePipe, RouterLink, IconComponent, SearchFilterBarComponent],
  templateUrl: './sessions.component.html',
  styleUrl: './sessions.component.scss',
})
export class SessionsComponent {
  protected readonly auth    = inject(AuthService);
  private  readonly sessApi  = inject(SessionService);
  private  readonly confirmDialog = inject(ConfirmDialogService);

  readonly activeFilter = signal<SessionStatus | 'ALL'>('ALL');
  readonly searchQuery  = signal('');
  readonly sessions     = signal<Session[] | null>(null);
  readonly total        = signal<number>(0);
  readonly loadError    = signal<string | null>(null);
  readonly actionError  = signal<string | null>(null);

  readonly counts = computed(() => {
    const all = this.sessions() ?? [];
    const result: Record<string, number> = { ALL: all.length };
    for (const f of STATUS_FILTERS) {
      if (f !== 'ALL') result[f] = all.filter(s => s.status === f).length;
    }
    return result;
  });

  readonly filterOptions = computed<FilterOption[]>(() => {
    const counts = this.counts();
    return STATUS_FILTERS.map(f => ({
      value: f,
      label: f === 'ALL' ? 'All statuses' : f.replace(/_/g, ' '),
      count: counts[f] ?? 0,
      color: FILTER_COLORS[f],
    }));
  });

  readonly visible = computed(() => {
    const all = this.sessions() ?? [];
    const f   = this.activeFilter();
    const q   = this.searchQuery().trim().toLowerCase();
    return all.filter(s =>
      (f === 'ALL' || s.status === f)
      && (!q || s.sessionId.toLowerCase().includes(q) || s.jobId.toLowerCase().includes(q)));
  });

  constructor() { this.load(); }

  load(): void {
    this.loadError.set(null);
    this.sessions.set(null);
    this.sessApi.list(0, 100).subscribe({
      next: page => {
        this.sessions.set(page.content);
        this.total.set(page.totalElements);
      },
      error: err => {
        this.sessions.set([]);
        this.loadError.set(err?.message ?? 'Request failed');
      },
    });
  }

  setFilter(f: string): void { this.activeFilter.set(f as SessionStatus | 'ALL'); }

  pause(session: Session): void {
    this.sessApi.pause(session.sessionId).subscribe({
      next:  updated => this.patchSession(updated),
      error: err     => this.actionError.set(err?.message ?? 'Action failed'),
    });
  }

  resume(session: Session): void {
    this.sessApi.resume(session.sessionId).subscribe({
      next:  updated => this.patchSession(updated),
      error: err     => this.actionError.set(err?.message ?? 'Action failed'),
    });
  }

  async delete(session: Session): Promise<void> {
    const ok = await this.confirmDialog.ask({
      message: `Delete session ${session.sessionId.substring(0, 8)}…? This cannot be undone.`,
      danger: true,
    });
    if (!ok) return;
    this.sessApi.delete(session.sessionId).subscribe({
      next: () => this.sessions.update(list =>
        list ? list.filter(s => s.sessionId !== session.sessionId) : list),
      error: err => this.actionError.set(err?.message ?? 'Delete failed'),
    });
  }

  private patchSession(updated: Session): void {
    this.sessions.update(list =>
      list ? list.map(s => s.sessionId === updated.sessionId ? updated : s) : list);
  }

  statusClass(status: string): string {
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
