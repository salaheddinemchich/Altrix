import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, inject, input, output, signal } from '@angular/core';
import { IconComponent } from '../icon/icon.component';

export interface FilterOption {
  value: string;
  label: string;
  count?: number;
  color?: string;
}

/**
 * One search input + one filter dropdown, sharing the same markup/styling
 * everywhere a list page needs "type to search" plus "narrow by status"
 * (Jobs, Sessions). Replaces the old always-visible status-pill row.
 */
@Component({
  selector: 'app-search-filter-bar',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './search-filter-bar.component.html',
  styleUrl: './search-filter-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SearchFilterBarComponent {
  private readonly el = inject(ElementRef<HTMLElement>);

  readonly searchPlaceholder = input<string>('Search…');
  readonly searchValue = input<string>('');
  readonly filters = input.required<FilterOption[]>();
  readonly activeFilter = input.required<string>();

  readonly searchValueChange = output<string>();
  readonly activeFilterChange = output<string>();

  readonly filterMenuOpen = signal(false);

  readonly activeOption = computed(() =>
    this.filters().find(f => f.value === this.activeFilter()) ?? this.filters()[0]);

  toggleFilterMenu(): void {
    this.filterMenuOpen.set(!this.filterMenuOpen());
  }

  selectFilter(value: string): void {
    this.activeFilterChange.emit(value);
    this.filterMenuOpen.set(false);
  }

  onSearchInput(ev: Event): void {
    this.searchValueChange.emit((ev.target as HTMLInputElement).value);
  }

  clearSearch(): void {
    this.searchValueChange.emit('');
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(ev: Event): void {
    if (!this.filterMenuOpen()) return;
    if (!this.el.nativeElement.contains(ev.target as Node)) this.filterMenuOpen.set(false);
  }
}
