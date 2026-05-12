import { Injectable, computed, inject } from '@angular/core';
import { AuthService } from '../auth/services/auth.service';

/**
 * Bridges the legacy {@code X-User-Id} header pipeline to the real authenticated
 * user. Backend services that pre-date the OAuth refactor still read this header;
 * we now wire it to {@code AuthService.user().userId} so the header always
 * carries the internal Altrix user UUID.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly auth = inject(AuthService);

  /** Current authenticated user's internal Altrix UUID — empty string if not signed in. */
  readonly currentUserId = computed(() => this.auth.user()?.userId ?? '');
}
