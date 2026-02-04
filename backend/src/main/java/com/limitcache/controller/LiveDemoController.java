package com.limitcache.controller;

import com.limitcache.model.LimitDTOs.*;
import com.limitcache.service.LimitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LiveDemoController {

    private final LimitService limitService;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    @GetMapping(value = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter liveDemoStream(
            @RequestParam(defaultValue = "20") int threads,
            @RequestParam(defaultValue = "2000") int totalRequests) {

        threads = Math.min(threads, 100);
        totalRequests = Math.min(totalRequests, 10000);

        SseEmitter emitter = new SseEmitter(120_000L);

        int finalThreads = threads;
        int finalTotalRequests = totalRequests;

        Thread demoThread = new Thread(() -> {
            try {
                runPhase(emitter, "CACHE", true, finalThreads, finalTotalRequests);
                Thread.sleep(1500);
                runPhase(emitter, "DB", false, finalThreads, finalTotalRequests);

                emitter.send(SseEmitter.event()
                        .name("demo_complete")
                        .data(LiveDemoEvent.builder()
                                .eventType("demo_complete")
                                .message("Demo finished")
                                .build()));
                emitter.complete();
            } catch (Exception e) {
                log.error("Live demo error: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });
        demoThread.setDaemon(true);
        demoThread.start();

        emitter.onTimeout(demoThread::interrupt);

        return emitter;
    }

    private int[] getPgContentionStats() {
        try {
            Integer active = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity " +
                    "WHERE state = 'active' AND pid != pg_backend_pid() AND datname = current_database()",
                    Integer.class);
            Integer waiting = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity " +
                    "WHERE wait_event_type = 'Lock' AND datname = current_database()",
                    Integer.class);
            Integer idleInTx = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity " +
                    "WHERE state = 'idle in transaction' AND datname = current_database()",
                    Integer.class);
            return new int[]{
                    active != null ? active : 0,
                    waiting != null ? waiting : 0,
                    idleInTx != null ? idleInTx : 0
            };
        } catch (Exception e) {
            return new int[]{0, 0, 0};
        }
    }

    /**
     * Query Redis INFO to get real-time stats.
     * Returns: [ops_per_sec, connected_clients, keyspace_hits, keyspace_misses, used_memory_human]
     */
    private Object[] getRedisStats() {
        try {
            return redisTemplate.execute((RedisConnection connection) -> {
                Properties info = connection.serverCommands().info();
                if (info == null) return new Object[]{0L, 0, 0L, 0L, "0B"};

                long opsPerSec = Long.parseLong(info.getProperty("instantaneous_ops_per_sec", "0"));
                int clients = Integer.parseInt(info.getProperty("connected_clients", "0"));
                long hits = Long.parseLong(info.getProperty("keyspace_hits", "0"));
                long misses = Long.parseLong(info.getProperty("keyspace_misses", "0"));
                String memory = info.getProperty("used_memory_human", "0B");

                return new Object[]{opsPerSec, clients, hits, misses, memory};
            });
        } catch (Exception e) {
            return new Object[]{0L, 0, 0L, 0L, "0B"};
        }
    }

    private void runPhase(SseEmitter emitter, String phase, boolean useCache,
                          int threads, int totalRequests) throws Exception {
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();

        limitService.resetLimitsForLoadTest(year, month);
        if (useCache) {
            limitService.warmCacheForCurrentMonth();
        }

        emitter.send(SseEmitter.event()
                .name("phase_start")
                .data(LiveDemoEvent.builder()
                        .phase(phase)
                        .eventType("phase_start")
                        .totalTargetRequests(totalRequests)
                        .message(phase + " test starting")
                        .build()));

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicInteger remaining = new AtomicInteger(totalRequests);
        AtomicLong lastLatency = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                Random random = new Random();
                while (remaining.decrementAndGet() >= 0) {
                    try {
                        ConsumeRequest req = ConsumeRequest.builder()
                                .date(LocalDate.now())
                                .amount((long) (100 + random.nextInt(900)))
                                .forceDirectDb(!useCache)
                                .build();
                        long reqStart = System.currentTimeMillis();
                        ConsumeResponse resp = limitService.consumeLimit(req);
                        long latency = System.currentTimeMillis() - reqStart;

                        completed.incrementAndGet();
                        totalLatency.addAndGet(latency);
                        lastLatency.set(latency);
                        if (!resp.isSuccess()) {
                            failed.incrementAndGet();
                        }
                    } catch (Exception e) {
                        completed.incrementAndGet();
                        failed.incrementAndGet();
                    }
                }
            }));
        }

        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(() -> {
            try {
                int comp = completed.get();
                long elapsed = System.currentTimeMillis() - startTime;
                double avg = comp > 0 ? totalLatency.get() / (double) comp : 0;
                double tps = comp * 1000.0 / Math.max(elapsed, 1);
                int queueDepth = totalRequests - comp;

                int[] pgStats = getPgContentionStats();
                Object[] redisStats = getRedisStats();

                emitter.send(SseEmitter.event()
                        .name("tick")
                        .data(LiveDemoEvent.builder()
                                .phase(phase)
                                .eventType("tick")
                                .completedRequests(comp)
                                .totalTargetRequests(totalRequests)
                                .failedRequests(failed.get())
                                .avgLatencyMs(Math.round(avg * 10.0) / 10.0)
                                .currentTps(Math.round(tps * 10.0) / 10.0)
                                .elapsedMs(elapsed)
                                .lastRequestLatencyMs(lastLatency.get())
                                .activeThreads(threads)
                                .queueDepth(Math.max(queueDepth, 0))
                                .pgActiveQueries(pgStats[0])
                                .pgWaitingOnLocks(pgStats[1])
                                .pgIdleInTransaction(pgStats[2])
                                .redisOpsPerSec((Long) redisStats[0])
                                .redisConnectedClients((Integer) redisStats[1])
                                .redisKeyspaceHits((Long) redisStats[2])
                                .redisKeyspaceMisses((Long) redisStats[3])
                                .redisUsedMemory((String) redisStats[4])
                                .build()));
            } catch (Exception e) {
                // emitter may be closed
            }
        }, 100, 100, TimeUnit.MILLISECONDS);

        for (Future<?> f : futures) {
            f.get();
        }
        reporter.shutdown();
        reporter.awaitTermination(1, TimeUnit.SECONDS);
        executor.shutdown();

        long finalElapsed = System.currentTimeMillis() - startTime;
        int comp = completed.get();
        double avg = comp > 0 ? totalLatency.get() / (double) comp : 0;
        double tps = comp * 1000.0 / Math.max(finalElapsed, 1);

        emitter.send(SseEmitter.event()
                .name("phase_end")
                .data(LiveDemoEvent.builder()
                        .phase(phase)
                        .eventType("phase_end")
                        .completedRequests(comp)
                        .totalTargetRequests(totalRequests)
                        .failedRequests(failed.get())
                        .avgLatencyMs(Math.round(avg * 10.0) / 10.0)
                        .currentTps(Math.round(tps * 10.0) / 10.0)
                        .elapsedMs(finalElapsed)
                        .queueDepth(0)
                        .message(phase + " completed in " + finalElapsed + "ms")
                        .build()));

        limitService.resetLimits(year, month);
    }
}
