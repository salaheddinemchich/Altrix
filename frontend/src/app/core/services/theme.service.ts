import { Injectable, signal } from '@angular/core';

export type Theme = 'dark' | 'light';

const STORAGE_KEY = 'altrix.theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly theme = signal<Theme>(this.readInitial());

  constructor() { this.apply(this.theme()); }

  toggle(): void {
    const next: Theme = this.theme() === 'dark' ? 'light' : 'dark';
    this.set(next);
  }

  set(theme: Theme): void {
    this.theme.set(theme);
    this.apply(theme);
    try { localStorage.setItem(STORAGE_KEY, theme); } catch { /* SSR / private mode */ }
  }

  private apply(theme: Theme): void {
    if (typeof document === 'undefined') return;
    document.documentElement.setAttribute('data-theme', theme);
  }

  private readInitial(): Theme {
    try {
      const stored = localStorage.getItem(STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') return stored;
    } catch { /* SSR */ }
    return 'dark';
  }
}
