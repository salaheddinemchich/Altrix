import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { catchError, of } from 'rxjs';
import {
  BranchStrategy,
  MigrationApplyResult,
  RepositoryAccess,
  RepositoryPermission,
} from '../../core/models/migration-apply.model';
import { MigrationApplyService } from '../../core/services/migration-apply.service';
import { IconComponent } from '../../shared/icon/icon.component';

/**
 * Migration Approval & Branch Strategy Workflow (#PR-feature).
 *
 * <p>Self-contained component that renders the full sequence:
 * <ol>
 *   <li>Permission check → show only strategies the user can pick.</li>
 *   <li>Branch-strategy picker (radio).</li>
 *   <li>Branch-name input (validated against a regex the server enforces too).</li>
 *   <li>Final-confirmation gate: checkbox + "Apply" button disabled until ticked.</li>
 *   <li>Result banner.</li>
 * </ol>
 *
 * <p>Inputs: {@link projectId} (to fetch permissions) and {@link sessionId}
 * (to apply against).  Emits {@link applied} when the workflow finishes
 * successfully so the parent can refresh its state.
 */
@Component({
  selector: 'app-migration-apply',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './migration-apply.component.html',
  styleUrl: './migration-apply.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MigrationApplyComponent {
  private readonly api = inject(MigrationApplyService);

  readonly sessionId = input.required<string>();

  /** Emitted on successful apply (or successful cancel). */
  readonly applied = output<MigrationApplyResult>();

  /** Mirrors the backend MigrationApplyConfig branch-name pattern.  Kept in
   *  sync with the YAML default — if you change it server-side, change here. */
  private readonly BRANCH_NAME_PATTERN = /^[A-Za-z0-9._/-]{1,128}$/;

  // ── access / permissions ────────────────────────────────────────────────
  readonly access = signal<RepositoryAccess | null>(null);
  readonly accessError = signal<string | null>(null);
  readonly accessLoading = signal<boolean>(false);

  // ── strategy choice ─────────────────────────────────────────────────────
  readonly selectedStrategy = signal<BranchStrategy | null>(null);
  /** The branch this action targets: merge-into for DIRECT_MERGE, base-of for NEW_BRANCH/PULL_REQUEST. */
  readonly selectedBaseBranch = signal<string>('');
  readonly branchName = signal<string>('');
  readonly commitMessage = signal<string>('');
  readonly prTitle = signal<string>('');
  readonly prBody = signal<string>('');

  // ── final-gate state ────────────────────────────────────────────────────
  readonly confirmationToken = signal<string | null>(null);
  readonly approvalChecked = signal<boolean>(false);
  readonly applying = signal<boolean>(false);

  // ── result ──────────────────────────────────────────────────────────────
  readonly result = signal<MigrationApplyResult | null>(null);
  readonly errorMessage = signal<string | null>(null);

  // Derived
  readonly needsBranchName = computed(() =>
    this.selectedStrategy() === 'NEW_BRANCH' || this.selectedStrategy() === 'PULL_REQUEST');

  readonly branchNameValid = computed(() => {
    if (!this.needsBranchName()) return true;
    const name = this.branchName().trim();
    if (name.length === 0) return true; // server will substitute a default
    return this.BRANCH_NAME_PATTERN.test(name);
  });

  readonly readyToOpenConfirm = computed(() =>
    this.selectedStrategy() != null
    && this.branchNameValid()
    && !!this.selectedBaseBranch());

  readonly applyDisabled = computed(() =>
    this.applying()
    || !this.confirmationToken()
    || !this.approvalChecked());

  constructor() {
    // Fetch permissions whenever the sessionId input changes.
    //
    // allowSignalWrites: we set accessLoading + accessError signals from
    // inside the effect — Angular 18 forbids that by default (NG0600).
    // Without this opt-in the effect throws on first render and the entire
    // <app-migration-apply> card stays blank.
    effect(() => {
      const sid = this.sessionId();
      if (!sid) return;
      this.accessLoading.set(true);
      this.accessError.set(null);
      this.api.getAccess(sid)
        .pipe(catchError(err => {
          this.accessError.set(typeof err?.error?.message === 'string'
            ? err.error.message
            : 'Could not check repository access.');
          return of<RepositoryAccess | null>(null);
        }))
        .subscribe(a => {
          this.accessLoading.set(false);
          if (a) {
            this.access.set(a);
            this.selectedBaseBranch.set(a.defaultBranch);
          }
        });
    }, { allowSignalWrites: true });
  }

  // ── actions ─────────────────────────────────────────────────────────────

  selectStrategy(s: BranchStrategy): void {
    this.selectedStrategy.set(s);
    this.selectedBaseBranch.set(this.access()?.defaultBranch ?? '');
    this.confirmationToken.set(null);
    this.approvalChecked.set(false);
    this.errorMessage.set(null);
  }

  selectBaseBranch(branch: string): void {
    this.selectedBaseBranch.set(branch);
    this.confirmationToken.set(null);
    this.approvalChecked.set(false);
    this.errorMessage.set(null);
  }

  /** Step 2 → step 3: ask server for a token. */
  openConfirmation(): void {
    const strategy = this.selectedStrategy();
    if (!strategy) return;
    this.errorMessage.set(null);
    this.api.setStrategy(this.sessionId(), {
      strategy,
      branchName:    this.branchName().trim() || undefined,
      baseBranch:    this.selectedBaseBranch().trim() || undefined,
      commitMessage: this.commitMessage().trim() || undefined,
      prTitle:       this.prTitle().trim() || undefined,
      prBody:        this.prBody().trim() || undefined,
    }).pipe(catchError(err => {
      this.errorMessage.set(err?.error?.message ?? 'Could not set branch strategy.');
      return of(null);
    })).subscribe(resp => {
      if (!resp) return;
      this.confirmationToken.set(resp.confirmationToken);
      // If the server filled in a default branch name (user left it blank),
      // reflect it back so the confirmation modal can show what will happen.
      if (resp.branchName && !this.branchName()) this.branchName.set(resp.branchName);
    });
  }

  /** User unticks / closes the modal without applying — clear approval. */
  closeConfirmation(): void {
    this.confirmationToken.set(null);
    this.approvalChecked.set(false);
    this.errorMessage.set(null);
  }

  /** Final apply: ticked + token + server-side re-validation. */
  confirmAndApply(): void {
    const token = this.confirmationToken();
    const strategy = this.selectedStrategy();
    if (!token || !strategy || !this.approvalChecked()) return;

    this.applying.set(true);
    this.errorMessage.set(null);
    this.api.confirm(this.sessionId(), {
      strategy,
      branchName:        this.branchName().trim() || undefined,
      commitMessage:     this.commitMessage().trim() || undefined,
      prTitle:           this.prTitle().trim() || undefined,
      prBody:            this.prBody().trim() || undefined,
      confirmationToken: token,
      userApproved:      true,
    }).pipe(catchError(err => {
      this.errorMessage.set(err?.error?.message ?? 'Apply failed.');
      return of<MigrationApplyResult | null>(null);
    })).subscribe(r => {
      this.applying.set(false);
      if (!r) return;
      this.result.set(r);
      this.confirmationToken.set(null);
      this.approvalChecked.set(false);
      if (r.success) this.applied.emit(r);
    });
  }

  /** Cancel button from the confirmation modal. */
  cancelEverything(): void {
    this.applying.set(true);
    this.api.cancel(this.sessionId())
      .pipe(catchError(() => of<MigrationApplyResult | null>(null)))
      .subscribe(r => {
        this.applying.set(false);
        this.confirmationToken.set(null);
        this.approvalChecked.set(false);
        if (r) this.result.set(r);
      });
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  strategyLabel(s: BranchStrategy): string {
    switch (s) {
      case 'DIRECT_MERGE':  return 'Merge directly into a branch';
      case 'NEW_BRANCH':    return 'Create a new branch';
      case 'PULL_REQUEST':  return 'Create a Pull Request';
    }
  }

  strategyDescription(s: BranchStrategy): string {
    switch (s) {
      case 'DIRECT_MERGE':  return 'Commit and merge straight into a branch you choose below. Requires admin permission.';
      case 'NEW_BRANCH':    return 'Push the migration to a new branch, created off a base branch you choose below.';
      case 'PULL_REQUEST':  return 'Push to a new branch and open a PR targeting a base branch you choose below.';
    }
  }

  strategyIcon(s: BranchStrategy): string {
    switch (s) {
      case 'DIRECT_MERGE':  return 'git-merge';
      case 'NEW_BRANCH':    return 'git-branch';
      case 'PULL_REQUEST':  return 'git-pull-request';
    }
  }

  /** Maps the repo permission level to a `.pill` colour variant (see styles.scss). */
  permissionPillClass(p: RepositoryPermission): string {
    switch (p) {
      case 'ADMIN': return 'gold';
      case 'WRITE': return 'info';
      case 'READ':  return 'warning';
      default:      return 'danger';
    }
  }

  resultBannerClass(r: MigrationApplyResult): string {
    if (r.success) return 'banner banner-success';
    if (r.outcome === 'CANCELLED') return 'banner banner-muted';
    return 'banner banner-error';
  }
}
