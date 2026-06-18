import { Injectable, signal } from '@angular/core';

export interface ConfirmRequest {
  message: string;
  title?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Red/destructive styling — for deletes and other irreversible actions. */
  danger?: boolean;
}

interface PendingConfirm extends ConfirmRequest {
  resolve: (value: boolean) => void;
}

/**
 * App-wide replacement for the browser's native {@code confirm()} — that
 * dialog is unstyled (shows the raw origin, can't be themed) and blocks the
 * JS thread. {@link ConfirmDialogComponent} renders the actual modal; it's
 * mounted once in the shell and reacts to {@link request}.
 */
@Injectable({ providedIn: 'root' })
export class ConfirmDialogService {
  readonly request = signal<PendingConfirm | null>(null);

  ask(req: ConfirmRequest | string): Promise<boolean> {
    const normalized: ConfirmRequest = typeof req === 'string' ? { message: req } : req;
    return new Promise<boolean>(resolve => {
      this.request.set({ ...normalized, resolve });
    });
  }

  resolve(result: boolean): void {
    const pending = this.request();
    if (!pending) return;
    this.request.set(null);
    pending.resolve(result);
  }
}
