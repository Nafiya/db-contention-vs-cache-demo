import { Component, OnDestroy, ElementRef, ViewChild, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { SseService, LiveDemoEvent } from '../../services/sse.service';

@Component({
  selector: 'app-live-demo',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './live-demo.component.html',
  styleUrls: ['./live-demo.component.css']
})
export class LiveDemoComponent implements OnDestroy, AfterViewChecked {
  @ViewChild('pgTerminalBody') pgTerminalBody?: ElementRef;
  @ViewChild('redisTerminalBody') redisTerminalBody?: ElementRef;
  private shouldScrollTerminal = false;

  threads = 20;
  totalRequests = 2000;

  demoState: 'idle' | 'running' | 'finished' = 'idle';
  currentPhase: 'CACHE' | 'DB' | null = null;

  cacheMetrics: LiveDemoEvent | null = null;
  dbMetrics: LiveDemoEvent | null = null;

  cachePipelineDots: { id: number; latency: number }[] = [];
  dbPipelineDots: { id: number; latency: number }[] = [];
  private dotCounter = 0;

  // Terminal log lines
  pgTerminalLines: string[] = [];
  redisTerminalLines: string[] = [];
  private tickCount = 0;

  cacheFinal: LiveDemoEvent | null = null;
  dbFinal: LiveDemoEvent | null = null;

  private subscription: Subscription | null = null;

  constructor(private sseService: SseService) {}

  ngAfterViewChecked() {
    if (this.shouldScrollTerminal) {
      if (this.pgTerminalBody) {
        const el = this.pgTerminalBody.nativeElement;
        el.scrollTop = el.scrollHeight;
      }
      if (this.redisTerminalBody) {
        const el = this.redisTerminalBody.nativeElement;
        el.scrollTop = el.scrollHeight;
      }
      this.shouldScrollTerminal = false;
    }
  }

  startDemo() {
    this.demoState = 'running';
    this.resetMetrics();

    this.subscription = this.sseService
      .connectLiveDemo(this.threads, this.totalRequests)
      .subscribe({
        next: (event) => this.handleEvent(event),
        complete: () => {}
      });
  }

  private handleEvent(event: LiveDemoEvent) {
    switch (event.eventType) {
      case 'phase_start':
        this.currentPhase = event.phase;
        this.tickCount = 0;
        this.addPhaseStartLine(event.phase);
        break;
      case 'tick':
        if (event.phase === 'CACHE') {
          this.cacheMetrics = event;
          this.addPipelineDot(this.cachePipelineDots, event.lastRequestLatencyMs);
          // CACHE phase is very fast — log every tick so lines are visible
          this.addTerminalLine(event);
        } else {
          this.dbMetrics = event;
          this.addPipelineDot(this.dbPipelineDots, event.lastRequestLatencyMs);
          // DB phase is slow — throttle to every 3rd tick to avoid flooding
          this.tickCount++;
          if (this.tickCount % 3 === 0) {
            this.addTerminalLine(event);
          }
        }
        break;
      case 'phase_end':
        if (event.phase === 'CACHE') {
          this.cacheFinal = event;
          this.addPhaseEndLine('CACHE', event.elapsedMs);
        } else {
          this.dbFinal = event;
          this.addPhaseEndLine('DB', event.elapsedMs);
        }
        this.currentPhase = null;
        break;
      case 'demo_complete':
        this.demoState = 'finished';
        break;
    }
  }

  private addPipelineDot(dots: { id: number; latency: number }[], latency: number) {
    dots.push({ id: this.dotCounter++, latency });
    if (dots.length > 50) {
      dots.shift();
    }
  }

  private addPhaseStartLine(phase: string) {
    if (phase === 'CACHE') {
      this.redisTerminalLines.push(`▶ Redis serving all requests via atomic EVALSHA script`);
    } else {
      this.pgTerminalLines.push(`▶ All requests hitting PostgreSQL directly (no cache)`);
    }
    this.shouldScrollTerminal = true;
  }

  private addPhaseEndLine(phase: string, elapsedMs: number) {
    if (phase === 'CACHE') {
      this.redisTerminalLines.push(`✓ COMPLETE — ${elapsedMs}ms`);
    } else {
      this.pgTerminalLines.push(`✓ COMPLETE — ${elapsedMs}ms`);
    }
    this.shouldScrollTerminal = true;
  }

  private addTerminalLine(event: LiveDemoEvent) {
    const ts = (event.elapsedMs / 1000).toFixed(1) + 's';

    if (event.phase === 'CACHE') {
      // Redis terminal — only during CACHE phase
      const ops = event.redisOpsPerSec || 0;
      const clients = event.redisConnectedClients || 0;
      const mem = event.redisUsedMemory || '0B';
      const hits = event.redisKeyspaceHits || 0;
      const misses = event.redisKeyspaceMisses || 0;
      const line = `[${ts}] EVALSHA limit_consume_script — ops/sec: ${ops} | clients: ${clients} | mem: ${mem} | hits: ${hits}, misses: ${misses}`;
      this.redisTerminalLines.push(line);
      if (this.redisTerminalLines.length > 100) this.redisTerminalLines.shift();
    } else {
      // PostgreSQL terminal — only during DB phase
      const waiting = event.pgWaitingOnLocks || 0;
      const active = event.pgActiveQueries || 0;
      const idle = event.pgIdleInTransaction || 0;
      let line: string;
      if (waiting > 0) {
        line = `[${ts}] pg_stat_activity: ${active} active, ${waiting} WAITING ON LOCKS, ${idle} idle_in_tx | avg_latency: ${event.avgLatencyMs}ms`;
      } else {
        line = `[${ts}] pg_stat_activity: ${active} active, ${idle} idle_in_tx | avg_latency: ${event.avgLatencyMs}ms`;
      }
      this.pgTerminalLines.push(line);
      if (this.pgTerminalLines.length > 100) this.pgTerminalLines.shift();
    }

    this.shouldScrollTerminal = true;
  }

  stopDemo() {
    this.subscription?.unsubscribe();
    this.demoState = 'idle';
  }

  ngOnDestroy() {
    this.subscription?.unsubscribe();
  }

  private resetMetrics() {
    this.cacheMetrics = null;
    this.dbMetrics = null;
    this.cacheFinal = null;
    this.dbFinal = null;
    this.cachePipelineDots = [];
    this.dbPipelineDots = [];
    this.currentPhase = null;
    this.dotCounter = 0;
    this.pgTerminalLines = [];
    this.redisTerminalLines = [];
    this.tickCount = 0;
  }

  getProgress(metrics: LiveDemoEvent | null): number {
    if (!metrics) return 0;
    return Math.round((metrics.completedRequests / metrics.totalTargetRequests) * 100);
  }

  getSpeedup(): string {
    if (!this.cacheFinal || !this.dbFinal || this.cacheFinal.elapsedMs === 0) return '0';
    return (this.dbFinal.elapsedMs / this.cacheFinal.elapsedMs).toFixed(1);
  }
}
