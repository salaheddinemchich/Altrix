import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/interceptors/auth.interceptor';
import { userIdInterceptor } from './core/interceptors/user-id.interceptor';

/**
 * Pure client-side rendering — no {@code provideClientHydration()}.
 *
 * <p>The dashboard is entirely behind an auth guard that reads
 * {@code sessionStorage}. On the server there is no storage, so the SSR
 * snapshot for any protected route is the login page. When the browser then
 * hydrates with the real token, the SSR-rendered {@code <app-login>} doesn't
 * match the client's {@code <app-shell>} tree and hydration bails — leaving
 * the user with a blank page. Pure CSR side-steps that mismatch entirely.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withFetch(),
      withInterceptors([authInterceptor, userIdInterceptor])
    ),
  ],
};
