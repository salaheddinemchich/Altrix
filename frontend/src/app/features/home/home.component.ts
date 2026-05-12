import { DatePipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../core/auth/services/auth.service';
import { JobStatus } from '../../core/models/job.model';
import { JobService } from '../../core/services/job.service';
import { ProjectService } from '../../core/services/project.service';
import { ProviderService } from '../../core/services/provider.service';
import { SessionService } from '../../core/services/session.service';
import { IconComponent } from '../../shared/icon/icon.component';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, DatePipe, IconComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.scss',
})
export class HomeComponent {
  protected readonly auth     = inject(AuthService);
  private  readonly projects  = inject(ProjectService);
  private  readonly jobs      = inject(JobService);
  private  readonly providers = inject(ProviderService);
  private  readonly sessions  = inject(SessionService);

  readonly projectList  = toSignal(this.projects.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly jobList      = toSignal(this.jobs.list().pipe(catchError(() => of([]))),       { initialValue: null });
  readonly providerList = toSignal(this.providers.list().pipe(catchError(() => of([]))),  { initialValue: null });
  readonly sessionPage  = toSignal(this.sessions.list(0, 1).pipe(catchError(() => of(null))), { initialValue: null });

  readonly projectCount  = computed(() => this.projectList()?.length ?? null);
  readonly jobCount      = computed(() => this.jobList()?.length ?? null);
  readonly enabledProvs  = computed(() => this.providerList()?.filter(p => p.effectiveEnabled).length ?? null);
  readonly sessionCount  = computed(() => this.sessionPage()?.totalElements ?? null);

  readonly recentJobs    = computed(() => (this.jobList() ?? []).slice(0, 6));
  readonly runningJobs   = computed(() =>
    (this.jobList() ?? []).filter(j => j.status === 'ANALYZING' || j.status === 'MIGRATING').length);

  statusClass(status: JobStatus): string {
    switch (status) {
      case 'DONE':      return 'success';
      case 'FAILED':    return 'danger';
      case 'ANALYZING':
      case 'MIGRATING': return 'info';
      case 'PENDING':   return 'warning';
      default:          return '';
    }
  }
}
