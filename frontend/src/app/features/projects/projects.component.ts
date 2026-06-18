import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Project } from '../../core/models/project.model';
import { ConfigFormatPreference, GitHubRepo } from '../../core/models/repository.model';
import { ProjectService } from '../../core/services/project.service';
import { RepositoryService } from '../../core/services/repository.service';
import { ConfirmDialogService } from '../../shared/confirm-dialog/confirm-dialog.service';
import { IconComponent } from '../../shared/icon/icon.component';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [FormsModule, DatePipe, IconComponent],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.scss',
})
export class ProjectsComponent {
  private readonly projectsApi = inject(ProjectService);
  private readonly repoApi = inject(RepositoryService);
  private readonly confirmDialog = inject(ConfirmDialogService);

  readonly projects = signal<Project[] | null>(null);
  readonly loadError = signal<string | null>(null);

  readonly repos = signal<GitHubRepo[] | null>(null);
  readonly reposError = signal<string | null>(null);
  readonly reposLoading = signal(false);

  readonly selectedRepo = signal<GitHubRepo | null>(null);
  readonly configFormat = signal<ConfigFormatPreference>('KEEP_ORIGINAL');
  readonly ingesting = signal(false);
  readonly ingestError = signal<string | null>(null);
  readonly ingestSuccess = signal<string | null>(null);

  readonly configOptions: ConfigFormatPreference[] = ['KEEP_ORIGINAL', 'YAML', 'PROPERTIES'];

  readonly repoSearchQuery = signal('');
  readonly filteredRepos = computed(() => {
    const q = this.repoSearchQuery().toLowerCase();
    const all = this.repos() ?? [];
    return q ? all.filter(r => r.fullName.toLowerCase().includes(q)) : all;
  });

  constructor() {
    this.refresh();
    this.loadRepos();
  }

  refresh(): void {
    this.loadError.set(null);
    this.projects.set(null);
    this.projectsApi.list().subscribe({
      next: list => this.projects.set(list),
      error: err => {
        this.projects.set([]);
        this.loadError.set(this.describe(err));
      },
    });
  }

  loadRepos(): void {
    this.reposLoading.set(true);
    this.reposError.set(null);
    this.repoApi.listRepos().subscribe({
      next: repos => {
        this.repos.set(repos);
        this.reposLoading.set(false);
      },
      error: err => {
        this.reposError.set(this.describe(err));
        this.reposLoading.set(false);
      },
    });
  }

  selectRepo(repo: GitHubRepo): void {
    this.selectedRepo.set(repo);
    this.ingestError.set(null);
    this.ingestSuccess.set(null);
  }

  clearSelection(): void {
    this.selectedRepo.set(null);
    this.ingestError.set(null);
    this.ingestSuccess.set(null);
  }

  ingest(): void {
    const repo = this.selectedRepo();
    if (!repo || this.ingesting()) return;
    this.ingesting.set(true);
    this.ingestError.set(null);
    this.ingestSuccess.set(null);

    this.repoApi.ingestFromGitHub({
      repoFullName: repo.fullName,
      defaultBranch: repo.defaultBranch,
      configFormatPreference: this.configFormat(),
    }).subscribe({
      next: project => {
        this.ingesting.set(false);
        this.ingestSuccess.set(`Project "${project.name}" created successfully.`);
        this.selectedRepo.set(null);
        this.refresh();
      },
      error: err => {
        this.ingesting.set(false);
        this.ingestError.set(this.describe(err));
      },
    });
  }

  async deleteProject(p: Project, ev: Event): Promise<void> {
    ev.stopPropagation();
    const ok = await this.confirmDialog.ask({
      message: `Delete project "${p.name}"? This cannot be undone.`,
      danger: true,
    });
    if (!ok) return;
    this.projectsApi.delete(p.id).subscribe({
      next: () => this.refresh(),
      error: err => alert('Delete failed: ' + this.describe(err)),
    });
  }

  statusClass(status: Project['status']): string {
    switch (status) {
      case 'READY':      return 'success';
      case 'FAILED':
      case 'ERROR':      return 'danger';
      case 'PROCESSING': return 'info';
      case 'PENDING':
      case 'REGISTERED': return 'warning';
      default:           return '';
    }
  }

  private describe(err: unknown): string {
    if (err && typeof err === 'object' && 'message' in err) return String((err as { message: unknown }).message);
    return 'Request failed';
  }
}
