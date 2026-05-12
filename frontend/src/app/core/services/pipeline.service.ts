import { Injectable, inject } from '@angular/core';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthService } from '../auth/services/auth.service';
import { ProgressEvent } from '../models/pipeline.model';

/**
 * Bridges the orchestrator's STOMP WebSocket topic `/topic/jobs/{jobId}` to RxJS.
 *
 * Lifecycle: a single shared Client is opened on first subscribe and closed
 * after the last subscriber unsubscribes — guarantees one socket per app.
 */
@Injectable({ providedIn: 'root' })
export class PipelineService {
  private readonly auth = inject(AuthService);

  private client: Client | null = null;
  private clientReady = false;
  private readonly pendingSubs: (() => void)[] = [];
  private activeSubs = 0;

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

  private ensureClient(): void {
    if (this.client) return;
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
        while (this.pendingSubs.length) this.pendingSubs.shift()!();
      },
    });
    this.client.activate();
  }

  private close(): void {
    this.client?.deactivate();
    this.client = null;
    this.clientReady = false;
    this.pendingSubs.length = 0;
  }
}
