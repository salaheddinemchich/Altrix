import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, inject, signal } from '@angular/core';
import { Router, RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/auth/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { ConfirmDialogComponent } from '../../shared/confirm-dialog/confirm-dialog.component';
import { IconComponent } from '../../shared/icon/icon.component';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent, ConfirmDialogComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly themeService = inject(ThemeService);
  private  readonly router = inject(Router);
  private  readonly el = inject(ElementRef<HTMLElement>);

  protected readonly user = this.auth.user;
  protected readonly isAdmin = this.auth.isAdmin;
  protected readonly theme = this.themeService.theme;

  protected readonly avatarUrl = computed(() => {
    const u = this.user();
    if (!u) return null;
    if (u.provider === 'GITHUB' && u.login) return `https://github.com/${u.login}.png?size=40`;
    return null;
  });

  /** `color` selects the `.nav-icon--*` badge variant (shell.component.scss). */
  protected readonly mainNav = [
    { path: '/',         icon: 'home',      label: 'Dashboard', exact: true,  color: 'gold'   },
    { path: '/projects', icon: 'folder',    label: 'Projects',  exact: false, color: 'blue'   },
    { path: '/jobs',     icon: 'briefcase', label: 'Jobs',      exact: false, color: 'teal'   },
    { path: '/sessions', icon: 'layers',    label: 'Sessions',  exact: false, color: 'purple' },
  ];

  /**
   * Each entry may carry an {@code adminOnly} flag; the template renders
   * it only when {@link #isAdmin} is true.  Keeping the flag on the data
   * (not in the template) means we add new admin-gated items by adding
   * one boolean, not by editing the markup.
   */
  protected readonly configNav: ReadonlyArray<{
    path: string; icon: string; label: string; color: string; adminOnly?: boolean;
  }> = [
    { path: '/providers', icon: 'cpu',         label: 'AI Providers', color: 'purple' },
    { path: '/billing',   icon: 'credit-card', label: 'Billing',      color: 'green', adminOnly: true },
  ];

  // ── Top bar: quick-navigate search ──────────────────────────────────────
  protected readonly searchQuery = signal('');
  protected readonly searchOpen = computed(() => this.searchQuery().trim().length > 0);

  private readonly allNavItems = computed(() => [
    ...this.mainNav,
    ...this.configNav.filter(i => !i.adminOnly || this.isAdmin()),
  ]);

  protected readonly searchResults = computed(() => {
    const q = this.searchQuery().trim().toLowerCase();
    if (!q) return [];
    return this.allNavItems().filter(i => i.label.toLowerCase().includes(q));
  });

  // ── Top bar: profile menu ───────────────────────────────────────────────
  protected readonly profileMenuOpen = signal(false);

  protected toggleProfileMenu(): void {
    this.profileMenuOpen.set(!this.profileMenuOpen());
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(ev: Event): void {
    if (!this.profileMenuOpen()) return;
    const wrap = this.el.nativeElement.querySelector('.profile-menu-wrap');
    if (wrap && !wrap.contains(ev.target as Node)) this.profileMenuOpen.set(false);
  }

  protected onSearchKeydown(ev: KeyboardEvent): void {
    if (ev.key === 'Enter') {
      const first = this.searchResults()[0];
      if (first) {
        this.router.navigate([first.path]);
        this.clearSearch();
      }
    } else if (ev.key === 'Escape') {
      this.clearSearch();
    }
  }

  protected clearSearch(): void {
    this.searchQuery.set('');
  }

  protected logout(): void {
    this.auth.logout();
    this.profileMenuOpen.set(false);
  }

  protected toggleTheme(): void {
    this.themeService.toggle();
  }

  protected roleLabel(role: string): string {
    switch (role) {
      case 'ROLE_ADMIN':       return 'Admin';
      case 'ROLE_SUPER_ADMIN': return 'Super Admin';
      default:                 return 'Member';
    }
  }
}
