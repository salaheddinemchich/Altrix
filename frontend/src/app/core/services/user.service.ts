import { Injectable, signal } from '@angular/core';

/**
 * Stand-in for real auth (issue #13 — GitHub OAuth).
 *
 * <p>Backend services expect every request to carry an {@code X-User-Id}
 * header. Until we wire OAuth, the demo app uses a fixed local-dev user.
 * Exposed as a signal so a future login flow can swap the value reactively.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  readonly currentUserId = signal<string>('demo-user');
}
