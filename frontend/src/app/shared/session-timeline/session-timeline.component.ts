import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { Subscription } from 'rxjs';
import {
  DEFAULT_PIPELINE,
  PipelineNode,
  PipelineNodeStatus,
  ProgressEvent,
} from '../../core/models/pipeline.model';
import { PipelineService } from '../../core/services/pipeline.service';
import { SessionService } from '../../core/services/session.service';
import { RagIndexManifest, SandboxLog } from '../../core/models/session.model';
import { IconComponent } from '../icon/icon.component';
import { catchError, of } from 'rxjs';

/**
 * Issue #114 — vertical session timeline.
 *
 * <p>Sibling visualisation to the horizontal {@code PipelineGraphComponent}:
 * the graph gives a fast at-a-glance view, this timeline gives the textual
 * detail (latest message + elapsed time per step).  Both share
 * {@link PipelineService} so a single WebSocket connection drives them
 * together.
 */
@Component({
  selector: 'app-session-timeline',
  standalone: true,
  imports: [DatePipe, IconComponent],
  templateUrl: './session-timeline.component.html',
  styleUrl: './session-timeline.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SessionTimelineComponent implements OnDestroy {
  private readonly pipelineApi = inject(PipelineService);
  private readonly sessionApi  = inject(SessionService);

  readonly jobId = input.required<string>();

  /**
   * Latest known session/job status — drives a backfill so a timeline opened
   * AFTER the job already ran shows the historical progress, not all-PENDING.
   * The WebSocket only emits live events; without this input a DONE job would
   * look frozen forever.
   */
  readonly currentStatus = input<string | null>(null);

  /**
   * Session id — when present, the Index step renders a "view indexed
   * files" toggle that lazy-loads the RAG manifest via SessionService.
   * Without it, the toggle is hidden.  Optional so the timeline stays
   * reusable in places where the session id isn't readily available.
   */
  readonly sessionId = input<string | null>(null);

  // ── RAG manifest lazy-load state ─────────────────────────────────────────
  /** True once the user expands the panel; triggers the HTTP fetch. */
  readonly ragManifestExpanded = signal<boolean>(false);
  readonly ragManifest = signal<RagIndexManifest | null>(null);
  readonly ragManifestLoading = signal<boolean>(false);
  /** True when the manifest endpoint returned 404 / empty.  Differentiates
   *  "still loading" from "nothing to show". */
  readonly ragManifestMissing = signal<boolean>(false);

  // ── Sandbox logs lazy-load state (#106) ──────────────────────────────────
  readonly sandboxLogsExpanded = signal<boolean>(false);
  readonly sandboxLogs = signal<SandboxLog[]>([]);
  readonly sandboxLogsLoading = signal<boolean>(false);
  readonly sandboxLogsMissing = signal<boolean>(false);
  /** Which runner's log the viewer currently shows.  null = no selection. */
  readonly selectedRunnerId = signal<string | null>(null);

  readonly selectedSandboxLog = computed<SandboxLog | null>(() => {
    const id = this.selectedRunnerId();
    return id ? this.sandboxLogs().find(l => l.runnerId === id) ?? null : null;
  });

  readonly steps = signal<TimelineStep[]>(initialSteps());
  readonly now   = signal<number>(Date.now());

  /** Re-emitted every second so elapsed times tick while a step is ACTIVE. */
  private readonly tickHandle: ReturnType<typeof setInterval>;

  readonly hasActive = computed(() =>
    this.steps().some(s => s.status === 'ACTIVE')
  );

  // ── Issue #115 — overall progress + ETA ─────────────────────────────────

  /**
   * Done steps count fully; an ACTIVE step counts as half-done so the bar
   * advances during a long agent run instead of jumping in 20% chunks.
   */
  readonly percentComplete = computed(() => {
    const list = this.steps();
    if (list.length === 0) return 0;
    const score = list.reduce((sum, s) => {
      if (s.status === 'DONE')   return sum + 1;
      if (s.status === 'ACTIVE') return sum + 0.5;
      return sum;
    }, 0);
    return Math.round((score / list.length) * 100);
  });

  /**
   * Heuristic ETA derived from observed runtimes on this run:
   *   avg(elapsed of DONE steps) × remaining (PENDING + ACTIVE) steps.
   * Returns null when no step has completed yet (no signal to project from).
   */
  readonly etaSeconds = computed<number | null>(() => {
    const list = this.steps();
    const now  = this.now();
    const completed = list.filter(s => s.status === 'DONE' && s.startedAt && s.endedAt);
    if (completed.length === 0) return null;

    const avgMs = completed.reduce((sum, s) => sum + (s.endedAt! - s.startedAt!), 0) / completed.length;
    const remaining = list.filter(s => s.status === 'PENDING' || s.status === 'ACTIVE').length;
    if (remaining === 0) return 0;

    // Subtract elapsed-so-far on any ACTIVE step from the projection so the
    // ETA shrinks while we watch it.
    const activeElapsed = list
      .filter(s => s.status === 'ACTIVE' && s.startedAt)
      .reduce((sum, s) => sum + (now - s.startedAt!), 0);

    const projectedMs = Math.max(0, avgMs * remaining - activeElapsed);
    return Math.round(projectedMs / 1000);
  });

  etaLabel(): string | null {
    const s = this.etaSeconds();
    if (s === null) return null;
    if (s === 0)    return 'almost done';
    return '~' + formatDuration(s * 1000) + ' remaining';
  }

  private sub: Subscription | null = null;

  constructor() {
    this.tickHandle = setInterval(() => {
      // Only repaint while a step is running — saves a render per second
      // once everything is DONE/ERROR.
      if (this.hasActive()) this.now.set(Date.now());
    }, 1000);

    // Angular 18 forbids writing to signals from an effect by default
    // (NG0600).  We intentionally seed `steps` here from jobId + currentStatus
    // before live WS events arrive — without allowSignalWrites the effect
    // throws and the backfill silently doesn't run.
    effect(() => {
      const id = this.jobId();
      const status = this.currentStatus();
      this.sub?.unsubscribe();
      // Reset + backfill atomically so an in-flight WebSocket apply() can't
      // race the reset and leave the timeline in an inconsistent state.
      this.steps.set(backfillSteps(initialSteps(), status));
      if (!id) return;
      this.sub = this.pipelineApi.watch(id).subscribe(evt => this.apply(evt));
    }, { allowSignalWrites: true });
  }


  ngOnDestroy(): void {
    this.sub?.unsubscribe();
    clearInterval(this.tickHandle);
  }

  // ── View helpers ─────────────────────────────────────────────────────────
  statusIcon(s: PipelineNodeStatus): string {
    switch (s) {
      case 'DONE':   return 'check';
      case 'ERROR':  return 'alert';
      case 'ACTIVE': return 'refresh';
      default:       return 'clock';
    }
  }

  elapsedLabel(step: TimelineStep): string {
    if (!step.startedAt) return '';
    const end = step.endedAt ?? this.now();
    const ms  = Math.max(0, end - step.startedAt);
    return formatDuration(ms);
  }

  /**
   * Toggles the "view sandbox logs" panel on the Validate step.  Same
   * lazy-load pattern as the RAG manifest — fetch on first open, then
   * just flip visibility.  Auto-selects the first log so the viewer
   * has something to show without an extra click.
   */
  toggleSandboxLogs(): void {
    const wasOpen = this.sandboxLogsExpanded();
    this.sandboxLogsExpanded.set(!wasOpen);
    if (wasOpen) return;
    if (this.sandboxLogs().length > 0 || this.sandboxLogsMissing()) return; // already loaded
    const id = this.sessionId();
    if (!id) return;

    this.sandboxLogsLoading.set(true);
    this.sessionApi.getSandboxLogs(id)
      .pipe(catchError(() => of<SandboxLog[]>([])))
      .subscribe(logs => {
        this.sandboxLogsLoading.set(false);
        if (logs && logs.length > 0) {
          this.sandboxLogs.set(logs);
          this.selectedRunnerId.set(logs[0].runnerId);
        } else {
          this.sandboxLogsMissing.set(true);
        }
      });
  }

  /** Switch which runner's log is shown in the viewer. */
  selectRunner(runnerId: string): void {
    this.selectedRunnerId.set(runnerId);
  }

  /**
   * Toggles the "view indexed files" panel on the Index step.  Fetches
   * the manifest the first time it's opened; subsequent toggles only
   * flip the visibility flag — no redundant HTTP calls.
   */
  toggleRagManifest(): void {
    const wasOpen = this.ragManifestExpanded();
    this.ragManifestExpanded.set(!wasOpen);
    if (wasOpen) return; // closing — nothing to do

    if (this.ragManifest() !== null || this.ragManifestMissing()) return; // already loaded
    const id = this.sessionId();
    if (!id) return;

    this.ragManifestLoading.set(true);
    this.sessionApi.getRagIndexManifest(id)
      .pipe(catchError(() => of(null)))
      .subscribe(m => {
        this.ragManifestLoading.set(false);
        if (m) this.ragManifest.set(m);
        else   this.ragManifestMissing.set(true);
      });
  }

  // ── Event application ────────────────────────────────────────────────────
  private apply(evt: ProgressEvent): void {
    const status = (evt.status ?? '').toUpperCase();
    const agent  = evt.agentName;
    const epoch  = parseTimestamp(evt.timestamp);

    this.steps.update(list => {
      const idx = list.findIndex(s => s.agents.includes(agent));
      if (idx < 0) return list;

      const next = [...list];
      const mapped = mapStatus(status);

      next[idx] = {
        ...next[idx],
        status:    mapped,
        message:   evt.message ?? next[idx].message,
        startedAt: next[idx].startedAt ?? epoch,
        endedAt:   (mapped === 'DONE' || mapped === 'ERROR') ? epoch : null,
        lastUpdate: epoch,
      };

      // Once a step reaches DONE / ACTIVE, any earlier still-PENDING step
      // must have completed in the meantime (the orchestrator skipped its
      // emission or it happened off-stream) — backfill them as DONE.
      if (mapped === 'DONE' || mapped === 'ACTIVE') {
        for (let i = 0; i < idx; i++) {
          if (next[i].status === 'PENDING') {
            next[i] = { ...next[i], status: 'DONE', endedAt: epoch };
          }
        }
      }
      return next;
    });
  }
}

// ── helpers ────────────────────────────────────────────────────────────────

interface TimelineStep extends PipelineNode {
  startedAt: number | null;
  endedAt:   number | null;
  lastUpdate?: number;
}

function initialSteps(): TimelineStep[] {
  return DEFAULT_PIPELINE.map(node => ({
    ...node,
    startedAt: null,
    endedAt:   null,
  }));
}

/**
 * Pure backfill: given a fresh step list and a coarse session/job status,
 * returns the step list with stages marked DONE / ACTIVE / ERROR so the
 * timeline reflects the current pipeline position even before any live
 * WebSocket event arrives.
 *
 * Steps: [0] Index  [1] Analyse  [2] Plan  [3] Migrate  [4] Validate  [5] Report
 *
 * Note: by the time the JOB status reaches ANALYZING, the orchestrator has
 * already finished RAG indexing (it runs first, then marks the job ANALYZING).
 * So ANALYZING implies Index = DONE.
 *
 *   PENDING                              none
 *   ANALYZING                            Index DONE, Analyse ACTIVE
 *   CONTEXT_ANALYSED                     +Plan ACTIVE
 *   PLAN_READY / AWAITING_APPROVAL       Index + Analyse + Plan DONE (gated)
 *   MIGRATING                            +Migrate ACTIVE
 *   VALIDATING                           +Validate ACTIVE
 *   DONE / COMPLETED                     all DONE
 *   FAILED                               first non-DONE → ERROR
 *   PAUSED                               keep existing state
 */
function backfillSteps(base: TimelineStep[], status: string | null | undefined): TimelineStep[] {
  if (!status) return base;
  const s = status.toUpperCase();
  const epoch = Date.now();

  if (s === 'PAUSED') return base;

  if (s === 'FAILED' || s === 'ERROR') {
    const next = [...base];
    let errIdx = next.findIndex(n => n.status !== 'DONE');
    if (errIdx === -1) errIdx = 0;
    next[errIdx] = { ...next[errIdx], status: 'ERROR', endedAt: epoch };
    return next;
  }

  let lastDone = -1;
  let active = -1;
  switch (s) {
    case 'PENDING':           lastDone = -1; active = -1; break;
    case 'ANALYZING':         lastDone = 0;  active = 1;  break;
    case 'CONTEXT_ANALYSED':  lastDone = 1;  active = 2;  break;
    case 'PLAN_READY':
    case 'AWAITING_APPROVAL': lastDone = 2;  active = -1; break;
    case 'MIGRATING':         lastDone = 2;  active = 3;  break;
    case 'VALIDATING':        lastDone = 3;  active = 4;  break;
    case 'DONE':
    case 'COMPLETED':         lastDone = 5;  active = -1; break;
    default: return base;
  }

  return base.map((step, i) => {
    if (i <= lastDone) {
      return { ...step, status: 'DONE' as PipelineNodeStatus,
               startedAt: step.startedAt ?? epoch,
               endedAt:   step.endedAt   ?? epoch };
    }
    if (i === active) {
      return { ...step, status: 'ACTIVE' as PipelineNodeStatus,
               startedAt: step.startedAt ?? epoch };
    }
    return step;
  });
}

function mapStatus(s: string): PipelineNodeStatus {
  switch (s) {
    case 'COMPLETED':
    case 'DONE':
    case 'SUCCESS':  return 'DONE';
    case 'FAILED':
    case 'ERROR':    return 'ERROR';
    case 'STARTED':
    case 'RUNNING':
    case 'ACTIVE':   return 'ACTIVE';
    default:         return 'PENDING';
  }
}

function parseTimestamp(ts: string | undefined): number {
  if (!ts) return Date.now();
  const parsed = Date.parse(ts);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}

/** "12s", "1m 04s", "2h 13m" — kept short so it fits in the timeline column. */
function formatDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  if (totalSec < 60)   return `${totalSec}s`;
  const min = Math.floor(totalSec / 60);
  const sec = totalSec % 60;
  if (min < 60)        return `${min}m ${sec.toString().padStart(2, '0')}s`;
  const hr = Math.floor(min / 60);
  return `${hr}h ${(min % 60).toString().padStart(2, '0')}m`;
}
