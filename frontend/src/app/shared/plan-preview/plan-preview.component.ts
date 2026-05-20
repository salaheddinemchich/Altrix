import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { MigrationPlan } from '../../core/models/session.model';

/**
 * Inline read-only preview of an AI-proposed {@link MigrationPlan} (#10).
 *
 * <p>Shown next to the Approve / Reject buttons so a reviewer can see what
 * they are about to approve.  Plan editing lands in a follow-up commit
 * (PATCH /api/v1/sessions/{id}/plan); for now this component is read-only.
 */
@Component({
  selector: 'app-plan-preview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './plan-preview.component.html',
  styleUrl: './plan-preview.component.scss',
})
export class PlanPreviewComponent {
  readonly plan = input.required<MigrationPlan>();

  riskClass(level: string): string {
    switch ((level || '').toUpperCase()) {
      case 'HIGH':   return 'risk-high';
      case 'MEDIUM': return 'risk-medium';
      case 'LOW':    return 'risk-low';
      default:       return '';
    }
  }
}
