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

  readonly steps = signal<TimelineStep[]>(initialSteps());
  readonly now   = signal<number>(Date.now());

  /** Re-emitted every second so elapsed times tick while a step is ACTIVE. */
  private readonly tickHandle: ReturnType<typeof setInterval>;

  readonly hasActive = computed(() =>
    this.steps().some(s => s.status === 'ACTIVE')
  );

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
      if (!id) return;
      this.sub = this.pipelineApi.watch(id).subscribe(evt => this.apply(evt));
    });
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
