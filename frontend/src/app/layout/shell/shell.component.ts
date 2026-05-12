import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../core/auth/services/auth.service';
import { ThemeService } from '../../core/services/theme.service';
import { IconComponent } from '../../shared/icon/icon.component';

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, IconComponent],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly themeService = inject(ThemeService);
  protected readonly user = this.auth.user;
  protected readonly isAdmin = this.auth.isAdmin;
  protected readonly theme = this.themeService.theme;

  protected readonly avatarUrl = computed(() => {
    const u = this.user();
    if (!u) return null;
    if (u.provider === 'GITHUB' && u.login) return `https://github.com/${u.login}.png?size=40`;
    return null;
  });

  protected readonly mainNav = [
    { path: '/',         icon: 'home',      label: 'Dashboard', exact: true  },
    { path: '/projects', icon: 'folder',    label: 'Projects',  exact: false },
    { path: '/jobs',     icon: 'briefcase', label: 'Jobs',      exact: false },
    { path: '/sessions', icon: 'layers',    label: 'Sessions',  exact: false },
  ];

  protected readonly configNav = [
    { path: '/providers', icon: 'cpu',         label: 'AI Providers' },
    { path: '/billing',   icon: 'credit-card', label: 'Billing'      },
  ];

  protected logout(): void {
    this.auth.logout();
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
