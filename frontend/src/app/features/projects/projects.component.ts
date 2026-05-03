import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ConfigFormatPreference, Project } from '../../core/models/project.model';
import { ProjectService } from '../../core/services/project.service';

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.scss'
})
export class ProjectsComponent {
  private readonly projectsApi = inject(ProjectService);

  readonly projects   = signal<Project[] | null>(null);
  readonly loadError  = signal<string | null>(null);

  readonly selectedFile          = signal<File | null>(null);
  readonly configFormat          = signal<ConfigFormatPreference>('KEEP_ORIGINAL');
  readonly uploading             = signal(false);
  readonly uploadError           = signal<string | null>(null);

  readonly configOptions: ConfigFormatPreference[] = ['KEEP_ORIGINAL', 'YAML', 'PROPERTIES'];

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.projects.set(null);
    this.projectsApi.list().subscribe({
      next: list => this.projects.set(list),
      error: err => {
        this.projects.set([]);
        this.loadError.set(this.describe(err));
      }
    });
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectedFile.set(input.files?.[0] ?? null);
    this.uploadError.set(null);
  }

  upload(): void {
    const file = this.selectedFile();
    if (!file || this.uploading()) return;

    this.uploading.set(true);
    this.uploadError.set(null);

    this.projectsApi.upload(file, this.configFormat()).subscribe({
      next: () => {
        this.uploading.set(false);
        this.selectedFile.set(null);
        this.refresh();
      },
      error: err => {
        this.uploading.set(false);
        this.uploadError.set(this.describe(err));
      }
    });
  }

  statusClass(status: Project['status']): string {
    switch (status) {
      case 'READY':      return 'success';
      case 'FAILED':     return 'danger';
      case 'PROCESSING': return 'info';
      case 'REGISTERED': return 'warning';
      default:           return '';
    }
  }

  private describe(err: unknown): string {
    if (err && typeof err === 'object' && 'message' in err) {
      return String((err as { message: unknown }).message);
    }
    return 'Request failed';
  }
}
