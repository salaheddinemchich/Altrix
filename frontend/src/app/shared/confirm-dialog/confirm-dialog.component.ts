import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { IconComponent } from '../icon/icon.component';
import { ConfirmDialogService } from './confirm-dialog.service';

/** Mounted once in {@code ShellComponent} — renders whatever request is
 *  currently pending on {@link ConfirmDialogService}. */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './confirm-dialog.component.html',
  styleUrl: './confirm-dialog.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConfirmDialogComponent {
  protected readonly svc = inject(ConfirmDialogService);
  protected readonly request = this.svc.request;

  confirm(): void {
    this.svc.resolve(true);
  }

  cancel(): void {
    this.svc.resolve(false);
  }
}
