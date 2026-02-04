package com.limitcache.service;

import com.limitcache.model.DailyLimit;
import com.limitcache.model.LimitCacheEntry;
import com.limitcache.repository.DailyLimitRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service responsible for Redis cache operations for daily limits.
 * Uses Redis Hashes for efficient storage and atomic operations.
 * 
 * Redis Data Structure:
 * - Key: "limits:2024:01" (year:month)
 * - Field: "day_15" (day of month)
 * - Value: JSON serialized LimitCacheEntry
 * 
 * For atomic decrements, we use separate keys:
 * - Key: "limits:remaining:2024:01:15" (specific day)
 * - Value: remaining amount (Long)
 */
@Service
@Slf4j
public class LimitCacheService {

    private final StringRedisTemplate redisTemplate;
    private final DailyLimitRepository dailyLimitRepository;
    private final MeterRegistry meterRegistry;

    @Value("${limit-cache.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${limit-cache.cache.key-prefix:limits}")
    private String keyPrefix;

    @Value("${limit-cache.cache.ttl-hours:24}")
    private int ttlHours;

    // Track dirty keys that need syncing
    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();

    // Metrics
    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter cacheUpdateCounter;
    private Timer cacheOperationTimer;

    // Lua script for atomic consume + metadata update in a single round-trip
    private static final String CONSUME_SCRIPT = """
        local remaining = redis.call('GET', KEYS[1])
        if remaining == false then
            return {-1, 0}  -- Key doesn't exist
        end
        remaining = tonumber(remaining)
        local amount = tonumber(ARGV[1])
        if remaining < amount then
            return {0, remaining}  -- Insufficient limit
        end
        local newRemaining = redis.call('DECRBY', KEYS[1], amount)
        -- Update metadata in the same script (KEYS[2] = metaKey)
        redis.call('HINCRBY', KEYS[2], 'consumed', amount)
        redis.call('HINCRBY', KEYS[2], 'transactionCount', 1)
        return {1, newRemaining}  -- Success
    """;

    private DefaultRedisScript<List> consumeScript;

    public LimitCacheService(StringRedisTemplate redisTemplate,
                             DailyLimitRepository dailyLimitRepository,
                             MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.dailyLimitRepository = dailyLimitRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        // Initialize metrics
        cacheHitCounter = Counter.builder("limit_cache_hits")
                .description("Number of cache hits")
                .register(meterRegistry);
        cacheMissCounter = Counter.builder("limit_cache_misses")
                .description("Number of cache misses")
                .register(meterRegistry);
        cacheUpdateCounter = Counter.builder("limit_cache_updates")
                .description("Number of cache updates")
                .register(meterRegistry);
        cacheOperationTimer = Timer.builder("limit_cache_operation_duration")
                .description("Cache operation duration")
                .register(meterRegistry);

        // Initialize Lua script
        consumeScript = new DefaultRedisScript<>();
        consumeScript.setScriptText(CONSUME_SCRIPT);
        consumeScript.setResultType(List.class);

        log.info("LimitCacheService initialized. Cache enabled: {}, TTL: {} hours", cacheEnabled, ttlHours);
    }

    /**
     * Check if caching is enabled
     */
    public boolean isCacheEnabled() {
        return cacheEnabled;
    }

    /**
     * Load limits for a month into cache
     */
    public int warmCache(int year, int month) {
        log.info("Warming cache for {}-{:02d}", year, month);
        
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1);
        
        List<DailyLimit> limits = dailyLimitRepository.findByMonth(startDate, endDate);
        
        for (DailyLimit limit : limits) {
            cacheLimit(limit);
        }
        
        log.info("Warmed cache with {} limits for {}-{:02d}", limits.size(), year, month);
        return limits.size();
    }

    /**
     * Cache a single daily limit
     */
    public void cacheLimit(DailyLimit limit) {
        String remainingKey = getRemainingKey(limit.getDayDate());
        String metaKey = getMetaKey(limit.getDayDate());
        
        // Store remaining amount as plain string for atomic operations (DECRBY)
        redisTemplate.opsForValue().set(remainingKey, String.valueOf(limit.getRemaining()),
                Duration.ofHours(ttlHours));

        // Store metadata as plain strings
        Map<String, String> meta = new HashMap<>();
        meta.put("initialLimit", String.valueOf(limit.getInitialLimit()));
        meta.put("consumed", String.valueOf(limit.getConsumed()));
        meta.put("transactionCount", String.valueOf(limit.getTransactionCount()));
        meta.put("version", String.valueOf(limit.getVersion()));
        meta.put("dayDate", limit.getDayDate().toString());

        redisTemplate.opsForHash().putAll(metaKey, meta);
        redisTemplate.expire(metaKey, Duration.ofHours(ttlHours));
        
        cacheUpdateCounter.increment();
    }

    /**
     * Get remaining limit from cache (or load from DB if miss)
     */
    public Optional<Long> getRemaining(LocalDate date) {
        return cacheOperationTimer.record(() -> {
            String key = getRemainingKey(date);
            String value = redisTemplate.opsForValue().get(key);

            if (value != null) {
                cacheHitCounter.increment();
                return Optional.of(Long.parseLong(value));
            }

            cacheMissCounter.increment();

            // Load from DB and cache
            return dailyLimitRepository.findByDayDate(date)
                    .map(limit -> {
                        cacheLimit(limit);
                        return limit.getRemaining();
                    });
        });
    }

    /**
     * Atomic consume operation using Lua script
     * @return ConsumeResult with success status and new remaining
     */
    public ConsumeResult consumeFromCache(LocalDate date, long amount) {
        String key = getRemainingKey(date);
        String metaKey = getMetaKey(date);

        // Execute atomic consume + metadata update in a single Lua script call
        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(consumeScript,
                Arrays.asList(key, metaKey),
                String.valueOf(amount));

        if (result == null || result.isEmpty()) {
            return new ConsumeResult(false, 0L, "Script execution failed");
        }

        long status = result.get(0);
        long newRemaining = result.get(1);

        if (status == -1) {
            // Cache miss - load from DB and retry once
            cacheMissCounter.increment();
            Optional<DailyLimit> limitOpt = dailyLimitRepository.findByDayDate(date);
            if (limitOpt.isEmpty()) {
                return new ConsumeResult(false, 0L, "Date not found");
            }
            cacheLimit(limitOpt.get());

            // Retry after warming
            @SuppressWarnings("unchecked")
            List<Long> retryResult = redisTemplate.execute(consumeScript,
                    Arrays.asList(key, metaKey),
                    String.valueOf(amount));

            if (retryResult == null || retryResult.isEmpty()) {
                return new ConsumeResult(false, 0L, "Script execution failed");
            }
            status = retryResult.get(0);
            newRemaining = retryResult.get(1);

            if (status <= 0) {
                return new ConsumeResult(false, newRemaining,
                        status == 0 ? "Insufficient limit" : "Key not found after warm");
            }
        } else if (status == 0) {
            return new ConsumeResult(false, newRemaining, "Insufficient limit");
        } else {
            cacheHitCounter.increment();
        }

        // Mark as dirty for sync
        dirtyKeys.add(key);
        cacheUpdateCounter.increment();

        return new ConsumeResult(true, newRemaining, "Success");
    }

    /**
     * Get full limit info from cache
     */
    public Optional<LimitCacheEntry> getLimitEntry(LocalDate date) {
        String remainingKey = getRemainingKey(date);
        String metaKey = getMetaKey(date);

        String remainingStr = redisTemplate.opsForValue().get(remainingKey);
        if (remainingStr == null) {
            cacheMissCounter.increment();
            return Optional.empty();
        }

        cacheHitCounter.increment();

        Map<Object, Object> meta = redisTemplate.opsForHash().entries(metaKey);

        return Optional.of(LimitCacheEntry.builder()
                .dayDate(date)
                .remaining(Long.parseLong(remainingStr))
                .initialLimit(meta.get("initialLimit") != null ?
                        Long.parseLong(meta.get("initialLimit").toString()) : 0L)
                .consumed(meta.get("consumed") != null ?
                        Long.parseLong(meta.get("consumed").toString()) : 0L)
                .transactionCount(meta.get("transactionCount") != null ?
                        Integer.parseInt(meta.get("transactionCount").toString()) : 0)
                .version(meta.get("version") != null ?
                        Integer.parseInt(meta.get("version").toString()) : 0)
                .build());
    }

    /**
     * Get all dirty keys that need syncing
     */
    public Set<String> getDirtyKeys() {
        return new HashSet<>(dirtyKeys);
    }

    /**
     * Mark keys as synced
     */
    public void markKeysSynced(Collection<String> keys) {
        dirtyKeys.removeAll(keys);
    }

    /**
     * Extract date from remaining key
     */
    public LocalDate extractDateFromKey(String key) {
        // Key format: limits:remaining:2024:01:15
        String[] parts = key.split(":");
        int year = Integer.parseInt(parts[2]);
        int month = Integer.parseInt(parts[3]);
        int day = Integer.parseInt(parts[4]);
        return LocalDate.of(year, month, day);
    }

    /**
     * Get cache statistics
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("enabled", cacheEnabled);
        stats.put("dirtyKeys", dirtyKeys.size());
        stats.put("keyPrefix", keyPrefix);
        stats.put("ttlHours", ttlHours);
        
        try {
            // Get Redis info
            Properties info = redisTemplate.getConnectionFactory()
                    .getConnection().serverCommands().info("memory");
            if (info != null) {
                stats.put("usedMemory", info.getProperty("used_memory_human"));
            }
        } catch (Exception e) {
            log.warn("Failed to get Redis stats: {}", e.getMessage());
        }
        
        return stats;
    }

    /**
     * Clear all cache entries
     */
    public void clearCache() {
        Set<String> keys = redisTemplate.keys(keyPrefix + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        dirtyKeys.clear();
        log.info("Cache cleared");
    }

    // Helper methods for key generation
    
    private String getRemainingKey(LocalDate date) {
        return String.format("%s:remaining:%d:%02d:%02d", 
                keyPrefix, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
    
    private String getMetaKey(LocalDate date) {
        return String.format("%s:meta:%d:%02d:%02d", 
                keyPrefix, date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    /**
     * Result of a consume operation
     */
    public record ConsumeResult(boolean success, long remaining, String message) {}
}
