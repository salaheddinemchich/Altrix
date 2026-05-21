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
import { IconComponent } from '../icon/icon.component';

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

  readonly jobId = input.required<string>();

  /**
   * Latest known session/job status — drives a backfill so a timeline opened
   * AFTER the job already ran shows the historical progress, not all-PENDING.
   * The WebSocket only emits live events; without this input a DONE job would
   * look frozen forever.
   */
  readonly currentStatus = input<string | null>(null);

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

    effect(() => {
      const id = this.jobId();
      this.sub?.unsubscribe();
      this.steps.set(initialSteps());
      this.applyStatusBackfill(this.currentStatus());
      if (!id) return;
      this.sub = this.pipelineApi.watch(id).subscribe(evt => this.apply(evt));
    });
  }

  /**
   * Backfills the timeline from a coarse session/job status so a freshly
   * opened JobDetail page doesn't show "0% / all PENDING" for a job that
   * already ran.  Live WebSocket events override this — once they start
   * arriving the per-step messages and elapsed times are accurate.
   *
   * Steps: [0] Analyse  [1] Plan  [2] Migrate  [3] Validate  [4] Report
   *
   * Mapping (lastDone is the last index that has finished, active is the
   * currently-running index or -1 when at a gate or terminal):
   *
   *   PENDING                              lastDone=-1  active=-1
   *   ANALYZING                            lastDone=-1  active=0    (Analyse running)
   *   CONTEXT_ANALYSED                     lastDone=0   active=1    (Plan running)
   *   PLAN_READY / AWAITING_APPROVAL       lastDone=1   active=-1   (gated at approval)
   *   MIGRATING                            lastDone=1   active=2    (Migrate running)
   *   VALIDATING                           lastDone=2   active=3    (Validate running)
   *   DONE / COMPLETED                     lastDone=4   active=-1   (all done)
   *   FAILED                               special — first PENDING step → ERROR
   *   PAUSED                               keep current state (don't override)
   */
  private applyStatusBackfill(status: string | null | undefined): void {
    if (!status) return;
    const s = status.toUpperCase();
    const epoch = Date.now();

    if (s === 'PAUSED') return;

    if (s === 'FAILED' || s === 'ERROR') {
      this.steps.update(list => {
        const next = [...list];
        let errIdx = next.findIndex(n => n.status !== 'DONE');
        if (errIdx === -1) errIdx = 0;
        next[errIdx] = { ...next[errIdx], status: 'ERROR', endedAt: epoch };
        return next;
      });
      return;
    }

    let lastDone = -1;
    let active = -1;
    switch (s) {
      case 'PENDING':           lastDone = -1; active = -1; break;
      case 'ANALYZING':         lastDone = -1; active = 0;  break;
      case 'CONTEXT_ANALYSED':  lastDone = 0;  active = 1;  break;
      case 'PLAN_READY':
      case 'AWAITING_APPROVAL': lastDone = 1;  active = -1; break;
      case 'MIGRATING':         lastDone = 1;  active = 2;  break;
      case 'VALIDATING':        lastDone = 2;  active = 3;  break;
      case 'DONE':
      case 'COMPLETED':         lastDone = 4;  active = -1; break;
      default: return;
    }

    this.steps.update(list => list.map((step, i) => {
      if (i <= lastDone) {
        return { ...step, status: 'DONE',
                 startedAt: step.startedAt ?? epoch,
                 endedAt:   step.endedAt   ?? epoch };
      }
      if (i === active) {
        return { ...step, status: 'ACTIVE',
                 startedAt: step.startedAt ?? epoch };
      }
      return step;
    }));
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
