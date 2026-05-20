import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/services/auth.service';
import { ProgressEvent } from '../models/pipeline.model';

/**
 * Connection state surfaced to the UI so components can fall back to
 * polling when the WebSocket is blocked (#118).
 *
 * <ul>
 *   <li>{@code idle}      — no subscribers yet, no connection attempt</li>
 *   <li>{@code connecting} — first or reconnect attempt in flight</li>
 *   <li>{@code connected}  — STOMP CONNECT frame acknowledged</li>
 *   <li>{@code unavailable} — gave up after {@link PipelineService#MAX_ATTEMPTS}
 *                            attempts; consumers should switch to REST polling</li>
 * </ul>
 */
export type WsConnectionState = 'idle' | 'connecting' | 'connected' | 'unavailable';

/**
 * Bridges the orchestrator's STOMP WebSocket topic `/topic/jobs/{jobId}` to RxJS.
 *
 * Lifecycle: a single shared Client is opened on first subscribe and closed
 * after the last subscriber unsubscribes — guarantees one socket per app.
 *
 * Resilience (#118): tracks consecutive failed connection attempts and exposes
 * a {@link connectionState} signal.  After {@link MAX_ATTEMPTS} the state
 * latches to {@code unavailable} so components can render a "Live updates
 * unavailable — polling every 10 s" banner and start REST polling instead.
 */
@Injectable({ providedIn: 'root' })
export class PipelineService {
  /** Hard cap on connection retries before the UI falls back to polling (#118). */
  private static readonly MAX_ATTEMPTS = 3;

  private readonly auth = inject(AuthService);

  private client: Client | null = null;
  private clientReady = false;
  private readonly pendingSubs: (() => void)[] = [];
  private activeSubs = 0;
  private failedAttempts = 0;

  /** Live connection state — UI components read this to decide on polling. */
  readonly connectionState = signal<WsConnectionState>('idle');

  watch(jobId: string): Observable<ProgressEvent> {
    const subject = new Subject<ProgressEvent>();
    let stompSub: StompSubscription | null = null;

    const subscribe = () => {
      stompSub = this.client!.subscribe(`/topic/jobs/${jobId}`, (msg: IMessage) => {
        try {
          const payload = JSON.parse(msg.body) as ProgressEvent;
          subject.next(payload);
        } catch {
          // ignore malformed payloads
        }
      });
    };

    this.ensureClient();
    if (this.clientReady) subscribe();
    else this.pendingSubs.push(subscribe);
    this.activeSubs++;

    return new Observable<ProgressEvent>(observer => {
      const sub = subject.subscribe(observer);
      return () => {
        sub.unsubscribe();
        stompSub?.unsubscribe();
        this.activeSubs--;
        if (this.activeSubs <= 0) this.close();
      };
    });
  }

  /** Lets the UI force a reconnect after the user dismisses the banner / clicks Retry. */
  retryConnection(): void {
    if (this.connectionState() === 'connected') return;
    this.failedAttempts = 0;
    this.close();
    if (this.activeSubs > 0) this.ensureClient();
  }

  private ensureClient(): void {
    if (this.client) return;
    if (this.connectionState() === 'unavailable') return; // give up — polling has the wheel

    this.connectionState.set('connecting');

    const url = `${environment.api.orchestrator}/ws`;
    const token = this.auth.accessToken();
    this.client = new Client({
      webSocketFactory: () => new SockJS(url) as WebSocket,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 4000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        this.clientReady = true;
        this.failedAttempts = 0;
        this.connectionState.set('connected');
        while (this.pendingSubs.length) this.pendingSubs.shift()!();
      },
      // Both errors flow through stompjs as separate callbacks — count either
      // toward the same retry budget so a flapping socket is treated the same
      // as one that never connects.
      onWebSocketError: () => this.recordFailure(),
      onStompError:     () => this.recordFailure(),
      onWebSocketClose: () => {
        // Only count a close as a failure if we never confirmed CONNECT —
        // otherwise stompjs' built-in reconnectDelay handles transient drops.
        if (!this.clientReady) this.recordFailure();
      },
    });
    this.client.activate();
  }

  private recordFailure(): void {
    this.failedAttempts++;
    if (this.failedAttempts >= PipelineService.MAX_ATTEMPTS) {
      this.connectionState.set('unavailable');
      // Stop stompjs from continuing to reconnect — consumer is now polling.
      this.client?.deactivate();
      this.client = null;
      this.clientReady = false;
    } else {
      this.connectionState.set('connecting');
    }
  }

  private close(): void {
    this.client?.deactivate();
    this.client = null;
    this.clientReady = false;
    this.pendingSubs.length = 0;
    this.connectionState.set('idle');
  }
}
