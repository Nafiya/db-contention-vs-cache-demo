import { Injectable, NgZone } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface LiveDemoEvent {
  phase: 'CACHE' | 'DB';
  eventType: 'tick' | 'phase_start' | 'phase_end' | 'demo_complete';
  completedRequests: number;
  totalTargetRequests: number;
  failedRequests: number;
  avgLatencyMs: number;
  currentTps: number;
  elapsedMs: number;
  lastRequestLatencyMs: number;
  activeThreads: number;
  queueDepth: number;
  message: string;
  pgActiveQueries: number;
  pgWaitingOnLocks: number;
  pgIdleInTransaction: number;
  redisOpsPerSec: number;
  redisConnectedClients: number;
  redisUsedMemory: string;
  redisKeyspaceHits: number;
  redisKeyspaceMisses: number;
}

@Injectable({ providedIn: 'root' })
export class SseService {
  private baseUrl = environment.apiUrl;

  constructor(private zone: NgZone) {}

  connectLiveDemo(threads: number, totalRequests: number): Observable<LiveDemoEvent> {
    return new Observable(observer => {
      const url = `${this.baseUrl}/demo/live?threads=${threads}&totalRequests=${totalRequests}`;
      const eventSource = new EventSource(url);

      const handleEvent = (eventName: string) => {
        eventSource.addEventListener(eventName, (event: MessageEvent) => {
          this.zone.run(() => {
            try {
              observer.next(JSON.parse(event.data));
            } catch (e) {
              observer.error(e);
            }
          });
        });
      };

      handleEvent('tick');
      handleEvent('phase_start');
      handleEvent('phase_end');
      handleEvent('demo_complete');

      eventSource.onerror = () => {
        this.zone.run(() => {
          eventSource.close();
          observer.complete();
        });
      };

      return () => eventSource.close();
    });
  }
}
