import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/guards/auth.guard';

export const routes: Routes = [
  // ── Auth routes — no shell ─────────────────────────────────────────────────
  {
    path: 'auth',
    children: [
      {
        path: 'login',
        title: 'Altrix — Sign in',
        canActivate: [guestGuard],
        loadComponent: () =>
          import('./features/auth/login/login.component').then(m => m.LoginComponent),
      },
      {
        path: 'callback',
        title: 'Altrix — Authenticating…',
        loadComponent: () =>
          import('./features/auth/callback/auth-callback.component').then(
            m => m.AuthCallbackComponent
          ),
      },
    ],
  },

  // ── Protected routes — wrapped by ShellComponent ───────────────────────────
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./layout/shell/shell.component').then(m => m.ShellComponent),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Altrix — Dashboard',
        loadComponent: () =>
          import('./features/home/home.component').then(m => m.HomeComponent),
      },
      {
        path: 'projects',
        title: 'Altrix — Projects',
        loadComponent: () =>
          import('./features/projects/projects.component').then(m => m.ProjectsComponent),
      },
      {
        path: 'jobs',
        title: 'Altrix — Jobs',
        loadComponent: () =>
          import('./features/jobs/jobs.component').then(m => m.JobsComponent),
      },
      {
        path: 'jobs/:id',
        title: 'Altrix — Job',
        loadComponent: () =>
          import('./features/jobs/job-detail.component').then(m => m.JobDetailComponent),
      },
      {
        path: 'sessions',
        title: 'Altrix — Sessions',
        loadComponent: () =>
          import('./features/sessions/sessions.component').then(m => m.SessionsComponent),
      },
      {
        path: 'sessions/:id/files',
        title: 'Altrix — Session diff',
        loadComponent: () =>
          import('./features/sessions/diff-viewer/diff-viewer.component').then(m => m.DiffViewerComponent),
      },
      {
        path: 'providers',
        title: 'Altrix — AI Providers',
        loadComponent: () =>
          import('./features/providers/providers.component').then(m => m.ProvidersComponent),
      },
      {
        path: 'billing',
        title: 'Altrix — Billing',
        loadComponent: () =>
          import('./features/billing/billing.component').then(m => m.BillingComponent),
      },
    ],
  },

  { path: '**', redirectTo: 'auth/login' },
];
