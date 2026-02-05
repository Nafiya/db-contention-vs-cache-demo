import { Injectable } from '@angular/core';
import { Observable, Observer } from 'rxjs';

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

  connectLiveDemo(threads: number, totalRequests: number): Observable<LiveDemoEvent> {
    return new Observable((observer: Observer<LiveDemoEvent>) => {
      let cancelled = false;

      const run = async () => {
        const half = Math.floor(totalRequests / 2);

        // ── CACHE PHASE ──
        observer.next(this.makeEvent('CACHE', 'phase_start', 0, half, 0, threads, 'Cache phase starting...'));

        const cacheTicks = 8;
        const cacheTickInterval = 50;
        for (let i = 1; i <= cacheTicks && !cancelled; i++) {
          await this.delay(cacheTickInterval);
          const completed = Math.round((i / cacheTicks) * half);
          const elapsed = i * cacheTickInterval;
          const latency = 0.5 + Math.random() * 1.5;
          const tps = 8000 + Math.random() * 4000;
          observer.next({
            phase: 'CACHE',
            eventType: 'tick',
            completedRequests: completed,
            totalTargetRequests: half,
            failedRequests: 0,
            avgLatencyMs: Math.round((0.8 + Math.random() * 0.8) * 100) / 100,
            currentTps: Math.round(tps),
            elapsedMs: elapsed,
            lastRequestLatencyMs: Math.round(latency * 100) / 100,
            activeThreads: threads,
            queueDepth: Math.floor(Math.random() * 3),
            message: `Processing with Redis cache: ${completed}/${half}`,
            pgActiveQueries: 0,
            pgWaitingOnLocks: 0,
            pgIdleInTransaction: 0,
            redisOpsPerSec: Math.round(12000 + Math.random() * 3000),
            redisConnectedClients: threads + Math.floor(Math.random() * 5),
            redisUsedMemory: `${(2.1 + Math.random() * 0.5).toFixed(2)}M`,
            redisKeyspaceHits: Math.round(completed * 0.98),
            redisKeyspaceMisses: Math.round(completed * 0.02),
          });
        }

        if (cancelled) return;
        const cacheElapsed = cacheTicks * cacheTickInterval;
        observer.next(this.makeEvent('CACHE', 'phase_end', half, half, cacheElapsed, threads, `Cache phase complete in ${cacheElapsed}ms`));

        // ── Pause between phases ──
        await this.delay(1500);
        if (cancelled) return;

        // ── DB PHASE ──
        observer.next(this.makeEvent('DB', 'phase_start', 0, half, 0, threads, 'Direct DB phase starting...'));

        const dbTicks = 30;
        const dbTickInterval = 80;
        for (let i = 1; i <= dbTicks && !cancelled; i++) {
          await this.delay(dbTickInterval);
          const progress = i / dbTicks;
          const completed = Math.round(progress * half);
          const elapsed = i * dbTickInterval;
          const baseLatency = 12 + progress * 18;
          const latency = baseLatency + Math.random() * 8;
          const tps = 1500 - progress * 600 + Math.random() * 200;
          const waiting = Math.min(Math.floor(progress * 18 + Math.random() * 4), 20);
          const active = Math.min(threads, 15 + Math.floor(progress * 6));
          const idleInTx = Math.floor(2 + progress * 4 + Math.random() * 2);
          observer.next({
            phase: 'DB',
            eventType: 'tick',
            completedRequests: completed,
            totalTargetRequests: half,
            failedRequests: Math.floor(progress * 3),
            avgLatencyMs: Math.round(latency * 100) / 100,
            currentTps: Math.round(tps),
            elapsedMs: elapsed,
            lastRequestLatencyMs: Math.round((latency + Math.random() * 5) * 100) / 100,
            activeThreads: threads,
            queueDepth: Math.floor(progress * 15 + Math.random() * 5),
            message: `Processing direct DB: ${completed}/${half}`,
            pgActiveQueries: active,
            pgWaitingOnLocks: waiting,
            pgIdleInTransaction: idleInTx,
            redisOpsPerSec: Math.round(30 + Math.random() * 40),
            redisConnectedClients: 2,
            redisUsedMemory: '2.10M',
            redisKeyspaceHits: 0,
            redisKeyspaceMisses: 0,
          });
        }

        if (cancelled) return;
        const dbElapsed = dbTicks * dbTickInterval;
        observer.next(this.makeEvent('DB', 'phase_end', half, half, dbElapsed, threads, `DB phase complete in ${dbElapsed}ms`));

        await this.delay(300);
        if (cancelled) return;

        observer.next(this.makeEvent('DB', 'demo_complete', half, half, dbElapsed, threads, 'Demo complete'));
        observer.complete();
      };

      run();

      return () => { cancelled = true; };
    });
  }

  private makeEvent(
    phase: 'CACHE' | 'DB',
    eventType: LiveDemoEvent['eventType'],
    completed: number,
    total: number,
    elapsed: number,
    threads: number,
    message: string
  ): LiveDemoEvent {
    return {
      phase,
      eventType,
      completedRequests: completed,
      totalTargetRequests: total,
      failedRequests: 0,
      avgLatencyMs: 0,
      currentTps: 0,
      elapsedMs: elapsed,
      lastRequestLatencyMs: 0,
      activeThreads: threads,
      queueDepth: 0,
      message,
      pgActiveQueries: 0,
      pgWaitingOnLocks: 0,
      pgIdleInTransaction: 0,
      redisOpsPerSec: 0,
      redisConnectedClients: 0,
      redisUsedMemory: '0B',
      redisKeyspaceHits: 0,
      redisKeyspaceMisses: 0,
    };
  }

  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}
