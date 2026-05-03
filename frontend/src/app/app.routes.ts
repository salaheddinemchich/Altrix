import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Altrix — Home',
    loadComponent: () =>
      import('./features/home/home.component').then(m => m.HomeComponent)
  },
  {
    path: 'projects',
    title: 'Altrix — Projects',
    loadComponent: () =>
      import('./features/projects/projects.component').then(m => m.ProjectsComponent)
  },
  {
    path: 'jobs',
    title: 'Altrix — Jobs',
    loadComponent: () =>
      import('./features/jobs/jobs.component').then(m => m.JobsComponent)
  },
  {
    path: 'jobs/:id',
    title: 'Altrix — Job',
    loadComponent: () =>
      import('./features/jobs/job-detail.component').then(m => m.JobDetailComponent)
  },
  {
    path: 'providers',
    title: 'Altrix — Providers',
    loadComponent: () =>
      import('./features/providers/providers.component').then(m => m.ProvidersComponent)
  },
  { path: '**', redirectTo: '' }
];
