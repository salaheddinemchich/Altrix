import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss'
})
export class AppComponent {
  readonly nav = [
    { path: '/',          icon: '◉', label: 'Home'      },
    { path: '/projects',  icon: '▦', label: 'Projects'  },
    { path: '/jobs',      icon: '⟳', label: 'Jobs'      },
    { path: '/providers', icon: '✦', label: 'AI Providers' }
  ];
}
