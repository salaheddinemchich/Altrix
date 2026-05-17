import { Injectable, computed, inject } from '@angular/core';
import { AuthService } from '../auth/services/auth.service';

/**
 * Bridges the legacy {@code X-User-Id} header pipeline to the real authenticated
 * user. Reads from {@link AuthService#userId}, which is derived from the JWT
 * {@code sub} claim — so the header is available on the first request after a
 * page reload, before /api/v1/auth/me has returned.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly auth = inject(AuthService);
  readonly currentUserId = computed(() => this.auth.userId());
}
