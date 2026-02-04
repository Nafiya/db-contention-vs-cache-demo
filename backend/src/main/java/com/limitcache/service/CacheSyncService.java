package com.limitcache.service;

import com.limitcache.model.LimitCacheEntry;
import com.limitcache.model.SyncHistory;
import com.limitcache.model.SyncHistory.SyncType;
import com.limitcache.repository.DailyLimitRepository;
import com.limitcache.repository.SyncHistoryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service responsible for syncing cache data back to the database.
 * Uses write-behind caching strategy - updates happen in cache first,
 * then periodically batched to database.
 */
@Service
@Slf4j
public class CacheSyncService {

    private final LimitCacheService cacheService;
    private final DailyLimitRepository dailyLimitRepository;
    private final SyncHistoryRepository syncHistoryRepository;
    private final MeterRegistry meterRegistry;

    @Value("${limit-cache.sync.enabled:true}")
    private boolean syncEnabled;

    @Value("${limit-cache.sync.interval-seconds:5}")
    private int syncIntervalSeconds;

    @Value("${limit-cache.sync.batch-size:100}")
    private int batchSize;

    @Value("${limit-cache.sync.retry-attempts:3}")
    private int retryAttempts;

    // Sync state tracking
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private Instant lastSuccessfulSync;
    private int lastSyncRecordCount;

    // Metrics
    private Counter syncSuccessCounter;
    private Counter syncFailedCounter;
    private Counter recordsSyncedCounter;
    private Timer syncDurationTimer;

    public CacheSyncService(LimitCacheService cacheService,
                           DailyLimitRepository dailyLimitRepository,
                           SyncHistoryRepository syncHistoryRepository,
                           MeterRegistry meterRegistry) {
        this.cacheService = cacheService;
        this.dailyLimitRepository = dailyLimitRepository;
        this.syncHistoryRepository = syncHistoryRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // Initialize metrics
        syncSuccessCounter = Counter.builder("cache_sync_success")
                .description("Successful cache syncs")
                .register(meterRegistry);
        syncFailedCounter = Counter.builder("cache_sync_failed")
                .description("Failed cache syncs")
                .register(meterRegistry);
        recordsSyncedCounter = Counter.builder("cache_sync_records")
                .description("Records synced to database")
                .register(meterRegistry);
        syncDurationTimer = Timer.builder("cache_sync_duration")
                .description("Cache sync duration")
                .register(meterRegistry);

        // Gauge for dirty keys count
        Gauge.builder("cache_dirty_keys", cacheService, 
                cs -> cs.getDirtyKeys().size())
                .description("Number of cache entries pending sync")
                .register(meterRegistry);

        log.info("CacheSyncService initialized. Sync enabled: {}, interval: {}s", 
                syncEnabled, syncIntervalSeconds);
    }

    /**
     * Scheduled sync job - runs every N seconds
     */
    @Scheduled(fixedDelayString = "${limit-cache.sync.interval-seconds:5}000")
    public void scheduledSync() {
        if (!syncEnabled || !cacheService.isCacheEnabled()) {
            return;
        }
        
        try {
            syncToDatabase(SyncType.SCHEDULED);
        } catch (Exception e) {
            log.error("Scheduled sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual sync trigger
     */
    public SyncResult manualSync() {
        return syncToDatabase(SyncType.MANUAL);
    }

    /**
     * Sync on application shutdown
     */
    @PreDestroy
    public void shutdownSync() {
        if (syncEnabled && cacheService.isCacheEnabled()) {
            log.info("Performing shutdown sync...");
            syncToDatabase(SyncType.SHUTDOWN);
        }
    }

    /**
     * Main sync logic - syncs dirty cache entries to database
     */
    @Transactional
    public SyncResult syncToDatabase(SyncType syncType) {
        // Prevent concurrent syncs
        if (!syncInProgress.compareAndSet(false, true)) {
            log.debug("Sync already in progress, skipping");
            return new SyncResult(false, 0, 0, "Sync already in progress");
        }

        SyncHistory history = SyncHistory.start(syncType);
        long startTime = System.currentTimeMillis();
        int syncedCount = 0;
        List<String> syncedKeys = new ArrayList<>();

        try {
            Set<String> dirtyKeys = cacheService.getDirtyKeys();
            
            if (dirtyKeys.isEmpty()) {
                log.debug("No dirty keys to sync");
                history.complete(0);
                syncHistoryRepository.save(history);
                return new SyncResult(true, 0, System.currentTimeMillis() - startTime, "No dirty keys");
            }

            log.debug("Syncing {} dirty keys to database", dirtyKeys.size());

            // Process in batches
            List<String> keyList = new ArrayList<>(dirtyKeys);
            for (int i = 0; i < keyList.size(); i += batchSize) {
                List<String> batch = keyList.subList(i, Math.min(i + batchSize, keyList.size()));
                
                for (String key : batch) {
                    try {
                        LocalDate date = cacheService.extractDateFromKey(key);
                        Optional<LimitCacheEntry> entryOpt = cacheService.getLimitEntry(date);
                        
                        if (entryOpt.isPresent()) {
                            LimitCacheEntry entry = entryOpt.get();
                            
                            int updated = dailyLimitRepository.syncFromCache(
                                    date,
                                    entry.getRemaining(),
                                    entry.getConsumed(),
                                    entry.getTransactionCount()
                            );
                            
                            if (updated > 0) {
                                syncedCount++;
                                syncedKeys.add(key);
                                recordsSyncedCounter.increment();
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Failed to sync key {}: {}", key, e.getMessage());
                    }
                }
            }

            // Mark synced keys as clean
            cacheService.markKeysSynced(syncedKeys);

            // Update tracking
            lastSuccessfulSync = Instant.now();
            lastSyncRecordCount = syncedCount;
            consecutiveFailures.set(0);

            // Record history
            history.complete(syncedCount);
            syncHistoryRepository.save(history);
            syncSuccessCounter.increment();

            long duration = System.currentTimeMillis() - startTime;
            syncDurationTimer.record(java.time.Duration.ofMillis(duration));

            log.info("Sync completed: {} records in {}ms", syncedCount, duration);
            return new SyncResult(true, syncedCount, duration, "Success");

        } catch (Exception e) {
            log.error("Sync failed: {}", e.getMessage(), e);
            
            consecutiveFailures.incrementAndGet();
            history.fail(e.getMessage());
            syncHistoryRepository.save(history);
            syncFailedCounter.increment();

            return new SyncResult(false, syncedCount, 
                    System.currentTimeMillis() - startTime, "Error: " + e.getMessage());
        } finally {
            syncInProgress.set(false);
        }
    }

    /**
     * Get sync statistics
     */
    public SyncStats getSyncStats() {
        Instant since = Instant.now().minusSeconds(3600); // Last hour
        
        Long totalSyncs = syncHistoryRepository.countByStatusSince(
                SyncHistory.SyncStatus.SUCCESS, since);
        Double avgDuration = syncHistoryRepository.getAvgDurationSince(since);
        Long totalRecords = syncHistoryRepository.getTotalRecordsSyncedSince(since);
        
        return SyncStats.builder()
                .enabled(syncEnabled)
                .intervalSeconds(syncIntervalSeconds)
                .lastSyncTime(lastSuccessfulSync)
                .lastSyncRecordCount(lastSyncRecordCount)
                .dirtyKeysCount(cacheService.getDirtyKeys().size())
                .consecutiveFailures(consecutiveFailures.get())
                .totalSyncsLastHour(totalSyncs != null ? totalSyncs : 0)
                .avgDurationMs(avgDuration != null ? avgDuration : 0.0)
                .totalRecordsSyncedLastHour(totalRecords != null ? totalRecords : 0)
                .build();
    }

    /**
     * Check if sync is healthy
     */
    public boolean isSyncHealthy() {
        if (!syncEnabled) {
            return true; // Not applicable
        }
        
        // Check for too many consecutive failures
        if (consecutiveFailures.get() >= 3) {
            return false;
        }
        
        // Check if last sync was too long ago (3x the interval)
        if (lastSuccessfulSync != null) {
            long secondsSinceLastSync = Instant.now().getEpochSecond() - 
                    lastSuccessfulSync.getEpochSecond();
            if (secondsSinceLastSync > syncIntervalSeconds * 3) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Sync result record
     */
    public record SyncResult(boolean success, int recordsSynced, long durationMs, String message) {}

    /**
     * Sync statistics
     */
    @lombok.Builder
    @lombok.Data
    public static class SyncStats {
        private boolean enabled;
        private int intervalSeconds;
        private Instant lastSyncTime;
        private int lastSyncRecordCount;
        private int dirtyKeysCount;
        private int consecutiveFailures;
        private long totalSyncsLastHour;
        private double avgDurationMs;
        private long totalRecordsSyncedLastHour;
    }
}
