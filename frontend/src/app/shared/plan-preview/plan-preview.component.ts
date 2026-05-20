import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MigrationPlan } from '../../core/models/session.model';
import { IconComponent } from '../icon/icon.component';

/**
 * Inline preview / editor for an AI-proposed {@link MigrationPlan} (#10).
 *
 * <p>Default mode is read-only.  When {@code editable} is true an "Edit"
 * button toggles into an editor where the reviewer can rewrite steps,
 * target files, summary, risk and estimated effort; "Save" emits
 * {@link #planSaved} with the new plan so the parent can PATCH it via
 * {@code SessionService.editPlan}.
 *
 * <p>Designed for the sessions list expansion row but works anywhere a
 * plan needs to be shown next to Approve / Reject.
 */
@Component({
  selector: 'app-plan-preview',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, IconComponent],
  templateUrl: './plan-preview.component.html',
  styleUrl: './plan-preview.component.scss',
})
export class PlanPreviewComponent {
  readonly plan       = input.required<MigrationPlan>();
  /** Shows the Edit button when true (admin-only on the sessions page). */
  readonly editable   = input<boolean>(false);
  /** Disables the Save button while a PATCH is in-flight. */
  readonly saving     = input<boolean>(false);

  /** Parent listens to this and PATCHes the plan via SessionService. */
  readonly planSaved  = output<MigrationPlan>();
  /** Optional — parent can react to cancel (e.g. close an expanded panel). */
  readonly cancelled  = output<void>();

  readonly mode = signal<'view' | 'edit'>('view');

  // ── Editor state (only populated while mode === 'edit') ────────────────
  readonly draftSteps    = signal<string>('');
  readonly draftFiles    = signal<string>('');
  readonly draftSummary  = signal<string>('');
  readonly draftRisk     = signal<string>('');
  readonly draftEffort   = signal<string>('');
  readonly draftStack    = signal<string>('');

  readonly draftStepCount  = computed(() => splitLines(this.draftSteps()).length);
  readonly draftFileCount  = computed(() => splitLines(this.draftFiles()).length);

  riskClass(level: string): string {
    switch ((level || '').toUpperCase()) {
      case 'HIGH':   return 'risk-high';
      case 'MEDIUM': return 'risk-medium';
      case 'LOW':    return 'risk-low';
      default:       return '';
    }
  }

  startEdit(): void {
    const p = this.plan();
    this.draftStack.set(p.targetStack || '');
    this.draftSteps.set((p.steps || []).join('\n'));
    this.draftFiles.set((p.targetFiles || []).join('\n'));
    this.draftSummary.set(p.summary || '');
    this.draftRisk.set(p.riskLevel || '');
    this.draftEffort.set(p.estimatedEffort || '');
    this.mode.set('edit');
  }

  cancelEdit(): void {
    this.mode.set('view');
    this.cancelled.emit();
  }

  save(): void {
    const edited: MigrationPlan = {
      targetStack:     this.draftStack().trim(),
      steps:           splitLines(this.draftSteps()),
      riskLevel:       this.draftRisk().trim(),
      estimatedEffort: this.draftEffort().trim(),
      summary:         this.draftSummary().trim(),
      targetFiles:     splitLines(this.draftFiles()),
    };
    this.planSaved.emit(edited);
    // Parent calls back via input updates after the PATCH resolves; we leave
    // edit mode here so the user immediately sees the new content.  If the
    // PATCH fails the parent re-emits the original plan + we stay in view.
    this.mode.set('view');
  }
}

/** "  a\n\nb \n c" → ["a", "b", "c"] — trims + drops blank lines. */
function splitLines(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map(s => s.trim())
    .filter(s => s.length > 0);
}
