import { ChangeDetectionStrategy, Component, OnDestroy, computed, effect, inject, input, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { DEFAULT_PIPELINE, PipelineNode, PipelineNodeStatus, ProgressEvent } from '../../core/models/pipeline.model';
import { PipelineService } from '../../core/services/pipeline.service';
import { IconComponent } from '../icon/icon.component';

/**
 * Real-time pipeline graph for a migration job.
 *
 * Renders a fixed five-stage flow (Analyse → Plan → Migrate → Validate → Report)
 * and updates each node's status as the orchestrator emits
 * {@link ProgressEvent}s over WebSocket. Edges between active nodes shimmer
 * via CSS animation; completed nodes turn gold.
 */
@Component({
  selector: 'app-pipeline-graph',
  standalone: true,
  imports: [IconComponent],
  templateUrl: './pipeline-graph.component.html',
  styleUrl: './pipeline-graph.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PipelineGraphComponent implements OnDestroy {
  private readonly pipelineApi = inject(PipelineService);

  readonly jobId = input.required<string>();
  readonly currentStatus = input<string | null>(null);

  readonly nodes = signal<PipelineNode[]>(structuredClone(DEFAULT_PIPELINE));
  readonly lastEvent = signal<ProgressEvent | null>(null);

  readonly activeIndex = computed(() =>
    this.nodes().findIndex(n => n.status === 'ACTIVE')
  );

  /**
   * Same "DONE counts fully, ACTIVE counts as half" formula as
   * SessionTimeline.percentComplete, so the header chip here and the
   * progress bar in the Timeline panel below always agree.
   */
  readonly progressPercent = computed(() => {
    const list = this.nodes();
    if (list.length === 0) return 0;
    const score = list.reduce((sum, n) => {
      if (n.status === 'DONE')   return sum + 1;
      if (n.status === 'ACTIVE') return sum + 0.5;
      return sum;
    }, 0);
    return Math.round((score / list.length) * 100);
  });

  private sub: Subscription | null = null;

  constructor() {
    // Angular 18 forbids writing to signals from an effect by default
    // (NG0600).  We intentionally write `nodes` here to seed the graph from
    // jobId + currentStatus before live WS events arrive — without
    // allowSignalWrites the effect throws and the backfill silently doesn't
    // run, which is what kept the JobDetail page stuck on all-PENDING.
    effect(() => {
      const id = this.jobId();
      this.sub?.unsubscribe();
      this.resetNodes();
      this.applyJobStatus(this.currentStatus());

      if (!id) return;
      this.sub = this.pipelineApi.watch(id).subscribe(evt => this.apply(evt));
    }, { allowSignalWrites: true });
  }

  ngOnDestroy(): void { this.sub?.unsubscribe(); }

  nodeIcon(status: PipelineNodeStatus): string {
    switch (status) {
      case 'DONE':   return 'check';
      case 'ERROR':  return 'alert';
      case 'ACTIVE': return 'refresh';
      default:       return 'clock';
    }
  }

  private apply(evt: ProgressEvent): void {
    this.lastEvent.set(evt);
    const status = evt.status?.toUpperCase();
    const agent = evt.agentName;

    this.nodes.update(list => {
      const idx = list.findIndex(n => n.agents.includes(agent));
      if (idx < 0) return list;

      const next = [...list];
      next[idx] = {
        ...next[idx],
        status: this.mapStatus(status),
        message: evt.message,
        updatedAt: evt.timestamp,
      };

      if (next[idx].status === 'DONE' || next[idx].status === 'ACTIVE') {
        for (let i = 0; i < idx; i++) {
          if (next[i].status === 'PENDING') next[i] = { ...next[i], status: 'DONE' };
        }
      }
      return next;
    });
  }

  private mapStatus(s: string | undefined): PipelineNodeStatus {
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

  /**
   * Map coarse session/job status to (lastDone, active) for the 5-stage graph.
   * Keeps PipelineGraph in sync with SessionTimeline's backfill logic so the
   * page reflects the real pipeline position even when no live WS events have
   * arrived yet.
   *
   *   PENDING                              none
   *   ANALYZING                            Analyse ACTIVE
   *   CONTEXT_ANALYSED                     Analyse DONE, Plan ACTIVE
   *   PLAN_READY / AWAITING_APPROVAL       Analyse + Plan DONE (gated)
   *   MIGRATING                            +Migrate ACTIVE
   *   VALIDATING                           +Validate ACTIVE
   *   DONE / COMPLETED                     all DONE
   *   FAILED                               first non-DONE → ERROR
   *   PAUSED                               keep existing state
   */
  private applyJobStatus(status: string | null | undefined): void {
    if (!status) return;
    const s = status.toUpperCase();

    if (s === 'PAUSED') return;

    if (s === 'FAILED' || s === 'ERROR') {
      this.nodes.update(list => {
        const next = [...list];
        let errIdx = next.findIndex(n => n.status !== 'DONE');
        if (errIdx === -1) errIdx = 0;
        next[errIdx] = { ...next[errIdx], status: 'ERROR' };
        return next;
      });
      return;
    }

    // Indices match DEFAULT_PIPELINE: [0] Analyse [1] Plan [2] Migrate
    // [3] Validate [4] Report.  See backfillSteps() in session-timeline for
    // the full mapping rationale.
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

    this.nodes.update(list => list.map((n, i) => {
      if (i <= lastDone) return { ...n, status: 'DONE' as PipelineNodeStatus };
      if (i === active)  return { ...n, status: 'ACTIVE' as PipelineNodeStatus };
      return n;
    }));
  }

  private resetNodes(): void {
    this.nodes.set(structuredClone(DEFAULT_PIPELINE));
    this.lastEvent.set(null);
  }
}
