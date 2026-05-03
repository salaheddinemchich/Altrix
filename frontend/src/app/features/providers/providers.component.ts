import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ProviderConfig, SaveProviderConfig } from '../../core/models/provider.model';
import { ProviderService } from '../../core/services/provider.service';

interface EditState {
  apiKey: string;
  modelAnalysis: string;
  modelMigration: string;
  enabled: boolean;
  saving: boolean;
  error: string | null;
}

@Component({
  selector: 'app-providers',
  standalone: true,
  imports: [FormsModule],
  templateUrl: './providers.component.html',
  styleUrl: './providers.component.scss'
})
export class ProvidersComponent {
  private readonly providersApi = inject(ProviderService);

  readonly providers = signal<ProviderConfig[] | null>(null);
  readonly loadError = signal<string | null>(null);

  readonly expandedId = signal<string | null>(null);
  readonly edits      = signal<Map<string, EditState>>(new Map());

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.loadError.set(null);
    this.providers.set(null);
    this.providersApi.list().subscribe({
      next: list => this.providers.set(list),
      error: err => {
        this.providers.set([]);
        this.loadError.set(err?.message ?? 'Request failed');
      }
    });
  }

  toggle(provider: ProviderConfig): void {
    if (this.expandedId() === provider.providerId) {
      this.expandedId.set(null);
      return;
    }

    const next = new Map(this.edits());
    if (!next.has(provider.providerId)) {
      next.set(provider.providerId, {
        apiKey: '',
        modelAnalysis:  provider.effectiveModelAnalysis  ?? '',
        modelMigration: provider.effectiveModelMigration ?? '',
        enabled:        provider.effectiveEnabled,
        saving:         false,
        error:          null
      });
      this.edits.set(next);
    }
    this.expandedId.set(provider.providerId);
  }

  edit(providerId: string): EditState | undefined {
    return this.edits().get(providerId);
  }

  patchEdit(providerId: string, patch: Partial<EditState>): void {
    const current = this.edits().get(providerId);
    if (!current) return;
    const next = new Map(this.edits());
    next.set(providerId, { ...current, ...patch });
    this.edits.set(next);
  }

  save(provider: ProviderConfig): void {
    const state = this.edits().get(provider.providerId);
    if (!state || state.saving) return;

    this.patchEdit(provider.providerId, { saving: true, error: null });

    const body: SaveProviderConfig = {
      enabled:        state.enabled,
      apiKey:         state.apiKey ? state.apiKey : null,
      baseUrl:        null,
      modelAnalysis:  state.modelAnalysis  || null,
      modelMigration: state.modelMigration || null
    };

    this.providersApi.update(provider.providerId, body).subscribe({
      next: () => {
        this.patchEdit(provider.providerId, { saving: false, apiKey: '' });
        this.refresh();
      },
      error: err => this.patchEdit(provider.providerId, {
        saving: false,
        error:  err?.message ?? 'Save failed'
      })
    });
  }
}
