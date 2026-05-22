import { Injectable, inject, signal } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';
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

  /** Grace period before tearing down the WS when activeSubs hits 0 (ms). */
  private static readonly CLOSE_GRACE_MS = 5_000;

  private client: Client | null = null;
  private clientReady = false;
  private readonly pendingSubs: (() => void)[] = [];
  private activeSubs = 0;
  private failedAttempts = 0;
  /**
   * Timer that lazily closes the WS some grace period after activeSubs hits 0.
   * Without it, every effect re-run in JobDetail cycles the connection:
   * unsubscribe → activeSubs=0 → close → new watch() → re-open.  Each cycle
   * loses any events sent in the gap and floods the console with false
   * "closed before CONNECT" warnings.
   */
  private closeTimer: ReturnType<typeof setTimeout> | null = null;

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

    // A new subscriber arrived — cancel any pending lazy close so we reuse
    // the existing connection instead of cycling it.
    if (this.closeTimer) {
      clearTimeout(this.closeTimer);
      this.closeTimer = null;
    }

    // Every new subscriber is a fresh intent to receive live updates.
    // If a previous burst of failures latched the state to 'unavailable',
    // reset the retry budget so this watcher gets a real attempt.  Without
    // this, once the connection failed three times early in the session
    // (e.g. while the backend was restarting), the service stays dead
    // permanently — even after the user navigates to a new job page.
    if (this.connectionState() === 'unavailable') {
      console.info('[PipelineService] resetting after previous unavailable state');
      this.failedAttempts = 0;
      this.connectionState.set('idle');
    }

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
        // Lazy close: don't tear down the WS the instant activeSubs hits 0
        // — effects in JobDetail re-run constantly (currentStatus changes
        // every 2 s poll), and each re-run unsubscribes + resubscribes.
        // Waiting a grace period lets the next watcher reuse the open
        // connection instead of cycling it.
        if (this.activeSubs <= 0 && !this.closeTimer) {
          this.closeTimer = setTimeout(() => {
            this.closeTimer = null;
            if (this.activeSubs <= 0) this.close();
          }, PipelineService.CLOSE_GRACE_MS);
        }
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

    // Same-origin '/ws' via the dev-server proxy (see proxy.conf.js).
    // SockJS xhr-streaming across origins (was 'http://localhost:8084/ws')
    // was being silently closed by Chrome right after the STOMP handshake
    // — surfacing as a "Normal closure" / "WebSocket closed before CONNECT"
    // loop.  Going same-origin sidesteps CORS quirks entirely.  In
    // production a gateway terminates /ws on the same host as the SPA, so
    // this works there too.
    const url = '/ws';
    const token = this.auth.accessToken();
    // Capture the client we're about to build in a local — onWebSocketClose
    // checks this.client === myClient to tell "this is the client we just
    // tore down" from "a real transport failure".  Avoids the setTimeout
    // race the previous intentionalClose flag had.
    let myClient: Client;
    let myClientReady = false;
    console.info('[PipelineService] connecting to', url, token ? '(with JWT)' : '(no JWT)');
    myClient = new Client({
      webSocketFactory: () => new SockJS(url) as WebSocket,
      connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
      reconnectDelay: 4000,
      heartbeatIncoming: 10_000,
      heartbeatOutgoing: 10_000,
      onConnect: () => {
        myClientReady = true;
        this.clientReady = true;
        this.failedAttempts = 0;
        this.connectionState.set('connected');
        console.info('[PipelineService] STOMP connected — flushing', this.pendingSubs.length, 'pending subscription(s)');
        while (this.pendingSubs.length) this.pendingSubs.shift()!();
      },
      onWebSocketError: (e) => {
        if (this.client !== myClient) return; // stale handler from a torn-down client
        console.warn('[PipelineService] WebSocket error', e);
        this.recordFailure();
      },
      onStompError: (frame) => {
        if (this.client !== myClient) return;
        console.warn('[PipelineService] STOMP error', frame?.headers, frame?.body);
        this.recordFailure();
      },
      onWebSocketClose: (e) => {
        // If we've already swapped to a new client (or closed intentionally),
        // this is a stale event from a torn-down connection — ignore it.
        if (this.client !== myClient) return;
        if (!myClientReady) {
          console.warn('[PipelineService] WebSocket closed before CONNECT — code', e?.code, 'reason', e?.reason);
          this.recordFailure();
        }
        // After CONNECT, transient closures are handled by stompjs's own
        // reconnectDelay — nothing for us to do here.
      },
    });
    this.client = myClient;
    myClient.activate();
  }

  private recordFailure(): void {
    this.failedAttempts++;
    console.warn('[PipelineService] failure', this.failedAttempts, '/', PipelineService.MAX_ATTEMPTS);
    if (this.failedAttempts >= PipelineService.MAX_ATTEMPTS) {
      this.connectionState.set('unavailable');
      console.warn('[PipelineService] giving up after', PipelineService.MAX_ATTEMPTS, 'attempts — falling back to REST polling');
      // Stop stompjs from continuing to reconnect — consumer is now polling.
      this.client?.deactivate();
      this.client = null;
      this.clientReady = false;
    } else {
      this.connectionState.set('connecting');
    }
  }

  private close(): void {
    // Setting this.client = null BEFORE deactivate() means the stale-handler
    // guard in onWebSocketClose (this.client !== myClient) trips for any
    // close event from this client — so the resulting "Normal closure" no
    // longer gets counted as a transport failure.  No flag/timeout race.
    const old = this.client;
    this.client = null;
    this.clientReady = false;
    this.pendingSubs.length = 0;
    old?.deactivate();
    this.connectionState.set('idle');
  }
}
