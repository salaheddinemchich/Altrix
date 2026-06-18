import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/services/auth.service';
import { Session, SessionStatus } from '../../core/models/session.model';
import { SessionService } from '../../core/services/session.service';
import { ConfirmDialogService } from '../../shared/confirm-dialog/confirm-dialog.service';
import { IconComponent } from '../../shared/icon/icon.component';

const STATUS_FILTERS: ReadonlyArray<SessionStatus | 'ALL'> = [
  'ALL', 'PENDING', 'CONTEXT_ANALYSED', 'PLAN_READY',
  'AWAITING_APPROVAL', 'MIGRATING', 'VALIDATING', 'DONE', 'FAILED', 'PAUSED',
];

@Component({
  selector: 'app-sessions',
  standalone: true,
  imports: [DatePipe, RouterLink, IconComponent],
  templateUrl: './sessions.component.html',
  styleUrl: './sessions.component.scss',
})
export class SessionsComponent {
  protected readonly auth    = inject(AuthService);
  private  readonly sessApi  = inject(SessionService);
  private  readonly confirmDialog = inject(ConfirmDialogService);

  readonly filters      = STATUS_FILTERS;
  readonly activeFilter = signal<SessionStatus | 'ALL'>('ALL');
  readonly sessions     = signal<Session[] | null>(null);
  readonly total        = signal<number>(0);
  readonly loadError    = signal<string | null>(null);
  readonly actionError  = signal<string | null>(null);

  readonly visible = computed(() => {
    const all = this.sessions() ?? [];
    const f   = this.activeFilter();
    return f === 'ALL' ? all : all.filter(s => s.status === f);
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

  setFilter(f: SessionStatus | 'ALL'): void { this.activeFilter.set(f); }

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
