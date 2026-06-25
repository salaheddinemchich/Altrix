import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, retry, timer, EMPTY } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AuthState, TokenResponse, UserProfile } from '../models/auth.models';

const ACCESS_TOKEN_KEY = 'altrix_access_token';

/** Decode the `sub` claim from a JWT without verifying its signature. */
function decodeJwtSub(token: string): string {
  try {
    const payload = token.split('.')[1];
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    return JSON.parse(json).sub ?? '';
  } catch {
    return '';
  }
}

/**
 * Singleton auth service — owns all auth state via signals.
 *
 * Token storage strategy:
 *   - Access token: sessionStorage (tab-scoped, cleared on close, never HttpOnly possible from JS)
 *   - Refresh token: HttpOnly cookie managed exclusively by the backend
 *
 * Security decisions:
 *   - No localStorage for tokens (persists across tabs/sessions, XSS risk)
 *   - Token extracted from URL fragment once then removed from history
 *   - Bearer header injected by AuthInterceptor, not here
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly apiBase = environment.apiUrl;

  // ── Signals ──────────────────────────────────────────────────────────────

  private readonly _state = signal<AuthState>({
    user: null,
    accessToken: this.readStoredToken(),
    loading: false,
    error: null,
  });

  readonly user = computed(() => this._state().user);
  readonly accessToken = computed(() => this._state().accessToken);
  readonly isAuthenticated = computed(() => !!this._state().accessToken);
  readonly isLoading = computed(() => this._state().loading);
  readonly error = computed(() => this._state().error);
  /**
   * True when the current user holds an admin-class role.  Mirrors the
   * backend's {@code hasAnyRole('ADMIN','SUPER_ADMIN')} so the frontend can
   * pre-emptively skip admin-only API calls (e.g. billing usage) for
   * non-admin users instead of issuing a request the server will 403.
   */
  readonly isAdmin = computed(() => {
    const r = this._state().user?.role;
    return r === 'ROLE_ADMIN' || r === 'ROLE_SUPER_ADMIN';
  });

  /**
   * Synchronous userId derived from the JWT `sub` claim — available the moment
   * the token is stored, without waiting for /api/v1/auth/me to return. This
   * lets the X-User-Id interceptor populate the header on the very first
   * request after a page reload.
   */
  readonly userId = computed<string>(() => {
    const u = this._state().user?.userId;
    if (u) return u;
    const tok = this._state().accessToken;
    return tok ? decodeJwtSub(tok) : '';
  });

  constructor() {
    // A token surviving in sessionStorage (e.g. after a manual page reload or
    // the browser suspending/discarding this tab) means the user is still
    // authenticated, but the in-memory `user` profile — only ever populated
    // once, right after the OAuth callback — is gone. Without this, the
    // profile chip/username silently disappears on the next reload despite
    // the session still being valid.
    if (this._state().accessToken) {
      this.loadProfile();
    }
  }

  // ── OAuth2 initiation ────────────────────────────────────────────────────

  initiateGitHubLogin(): void {
    window.location.href = `${this.apiBase}/oauth2/authorization/github`;
  }

  initiateGoogleLogin(): void {
    window.location.href = `${this.apiBase}/oauth2/authorization/google`;
  }

  initiateGitLabLogin(): void {
    window.location.href = `${this.apiBase}/oauth2/authorization/gitlab`;
  }

  // ── Callback handling ────────────────────────────────────────────────────

  handleCallback(token: string): void {
    this.storeToken(token);
    this._state.update(s => ({ ...s, accessToken: token, error: null }));
    this.loadProfile();
  }

  // ── Profile ──────────────────────────────────────────────────────────────

  loadProfile(): void {
    this._state.update(s => ({ ...s, loading: true }));
    this.http.get<UserProfile>(`${this.apiBase}/api/v1/auth/me`).pipe(
      // A transient failure here (backend mid-restart, brief network blip on
      // reload) must not permanently blank the profile chip for the rest of
      // the tab's life — retry twice with backoff before giving up.
      retry({ count: 2, delay: () => timer(1500) }),
      tap(user => this._state.update(s => ({ ...s, user, loading: false }))),
      catchError(err => {
        this._state.update(s => ({ ...s, loading: false, error: 'Failed to load profile' }));
        return EMPTY;
      })
    ).subscribe();
  }

  // ── Token refresh ────────────────────────────────────────────────────────

  refresh(): Observable<TokenResponse> {
    return this.http.post<TokenResponse>(
      `${this.apiBase}/api/v1/auth/refresh`, {}, { withCredentials: true }
    ).pipe(
      tap(res => {
        this.storeToken(res.accessToken);
        this._state.update(s => ({ ...s, accessToken: res.accessToken, error: null }));
      })
    );
  }

  // ── Logout ───────────────────────────────────────────────────────────────

  logout(): void {
    this.http.post(`${this.apiBase}/api/v1/auth/logout`, {}, { withCredentials: true }).pipe(
      catchError(() => EMPTY)
    ).subscribe(() => this.clearSession());
  }

  clearSession(): void {
    sessionStorage.removeItem(ACCESS_TOKEN_KEY);
    this._state.set({ user: null, accessToken: null, loading: false, error: null });
    this.router.navigate(['/auth/login']);
  }

  // ── Token helpers ─────────────────────────────────────────────────────────

  private storeToken(token: string): void {
    sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
  }

  private readStoredToken(): string | null {
    if (typeof window === 'undefined') return null;
    return sessionStorage.getItem(ACCESS_TOKEN_KEY);
  }
}
