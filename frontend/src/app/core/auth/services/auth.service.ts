import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap, catchError, EMPTY } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AuthState, TokenResponse, UserProfile } from '../models/auth.models';

const ACCESS_TOKEN_KEY = 'altrix_access_token';

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
  readonly isAdmin = computed(() => this._state().user?.role === 'ROLE_ADMIN');

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
