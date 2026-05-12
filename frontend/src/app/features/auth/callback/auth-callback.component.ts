import { ChangeDetectionStrategy, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../../core/auth/services/auth.service';
import { NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';

/**
 * Handles the OAuth2 redirect from the backend.
 *
 * The backend appends `?token=<accessJwt>` to the configured redirect URI.
 * This component:
 *   1. Reads the token from the URL query param
 *   2. Hands it to AuthService (which stores it and loads profile)
 *   3. Removes the token from the browser history so it never sits in the URL bar
 *   4. Redirects to the intended destination (or home)
 */
@Component({
  selector: 'app-auth-callback',
  standalone: true,
  imports: [NgIf, RouterLink],
  templateUrl: './auth-callback.component.html',
  styleUrl: './auth-callback.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AuthCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected error = false;

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');

    if (!token) {
      this.error = true;
      return;
    }

    // Remove token from URL immediately — it must not linger in history
    window.history.replaceState({}, '', '/auth/callback');

    this.auth.handleCallback(token);
    this.router.navigate(['/'], { replaceUrl: true });
  }
}
