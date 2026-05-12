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

  private sub: Subscription | null = null;

  constructor() {
    effect(() => {
      const id = this.jobId();
      this.sub?.unsubscribe();
      this.resetNodes();
      this.applyJobStatus(this.currentStatus());

      if (!id) return;
      this.sub = this.pipelineApi.watch(id).subscribe(evt => this.apply(evt));
    });
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

  private applyJobStatus(status: string | null | undefined): void {
    if (!status) return;
    const s = status.toUpperCase();
    if (s === 'COMPLETED' || s === 'DONE' || s === 'SUCCESS') {
      this.nodes.update(list => list.map(n => ({ ...n, status: 'DONE' as PipelineNodeStatus })));
    } else if (s === 'FAILED' || s === 'ERROR') {
      this.nodes.update(list => {
        const next = [...list];
        const lastDone = [...next].reverse().findIndex(n => n.status === 'DONE');
        const errIdx = lastDone === -1 ? 0 : next.length - lastDone;
        if (next[errIdx]) next[errIdx] = { ...next[errIdx], status: 'ERROR' };
        return next;
      });
    }
  }

  private resetNodes(): void {
    this.nodes.set(structuredClone(DEFAULT_PIPELINE));
    this.lastEvent.set(null);
  }
}
