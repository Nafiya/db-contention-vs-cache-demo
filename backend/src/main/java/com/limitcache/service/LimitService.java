package com.limitcache.service;

import com.limitcache.model.DailyLimit;
import com.limitcache.model.LimitCacheEntry;
import com.limitcache.model.LimitDTOs.*;
import com.limitcache.repository.DailyLimitRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main service for limit operations.
 * Orchestrates between cache and database based on configuration.
 */
@Service
@Slf4j
public class LimitService {

    private final LimitCacheService cacheService;
    private final DailyLimitRepository dailyLimitRepository;
    private final MeterRegistry meterRegistry;

    // Metrics
    private Counter consumeSuccessCounter;
    private Counter consumeFailedCounter;
    private Counter consumeInsufficientCounter;
    private Timer consumeTimer;
    private Timer directDbTimer;

    public LimitService(LimitCacheService cacheService,
                        DailyLimitRepository dailyLimitRepository,
                        MeterRegistry meterRegistry) {
        this.cacheService = cacheService;
        this.dailyLimitRepository = dailyLimitRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        consumeSuccessCounter = Counter.builder("limit_consume_success")
                .description("Successful limit consumptions")
                .register(meterRegistry);
        consumeFailedCounter = Counter.builder("limit_consume_failed")
                .description("Failed limit consumptions")
                .register(meterRegistry);
        consumeInsufficientCounter = Counter.builder("limit_consume_insufficient")
                .description("Insufficient limit errors")
                .register(meterRegistry);
        consumeTimer = Timer.builder("limit_consume_duration")
                .description("Limit consumption duration (cache mode)")
                .tag("mode", "cache")
                .register(meterRegistry);
        directDbTimer = Timer.builder("limit_consume_duration")
                .description("Limit consumption duration (direct DB mode)")
                .tag("mode", "direct_db")
                .register(meterRegistry);

        // Warm cache on startup
        if (cacheService.isCacheEnabled()) {
            warmCacheForCurrentMonth();
        }
    }

    /**
     * Warm cache for current and next month
     */
    public void warmCacheForCurrentMonth() {
        LocalDate now = LocalDate.now();
        cacheService.warmCache(now.getYear(), now.getMonthValue());
        
        // Also warm next month if we're in the last week
        if (now.getDayOfMonth() >= 24) {
            LocalDate nextMonth = now.plusMonths(1);
            cacheService.warmCache(nextMonth.getYear(), nextMonth.getMonthValue());
        }
    }

    /**
     * Consume limit - main entry point
     */
    public ConsumeResponse consumeLimit(ConsumeRequest request) {
        long startTime = System.currentTimeMillis();
        
        try {
            ConsumeResponse response;
            
            if (cacheService.isCacheEnabled() && !request.isForceDirectDb()) {
                response = consumeViaCache(request);
            } else {
                response = consumeDirectDb(request);
            }
            
            response.setLatencyMs(System.currentTimeMillis() - startTime);
            
            if (response.isSuccess()) {
                consumeSuccessCounter.increment();
            } else if ("Insufficient limit".equals(response.getMessage())) {
                consumeInsufficientCounter.increment();
            } else {
                consumeFailedCounter.increment();
            }
            
            return response;
            
        } catch (Exception e) {
            log.error("Error consuming limit: {}", e.getMessage(), e);
            consumeFailedCounter.increment();
            
            return ConsumeResponse.builder()
                    .success(false)
                    .transactionId(request.getTransactionId())
                    .date(request.getDate())
                    .amountConsumed(0L)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .message("Error: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Consume via Redis cache (fast path)
     */
    private ConsumeResponse consumeViaCache(ConsumeRequest request) {
        return consumeTimer.record(() -> {
            LimitCacheService.ConsumeResult result = cacheService.consumeFromCache(
                    request.getDate(), request.getAmount());
            
            return ConsumeResponse.builder()
                    .success(result.success())
                    .transactionId(request.getTransactionId() != null ? 
                            request.getTransactionId() : UUID.randomUUID().toString())
                    .date(request.getDate())
                    .amountConsumed(result.success() ? request.getAmount() : 0L)
                    .remainingLimit(result.remaining())
                    .source("CACHE")
                    .message(result.message())
                    .build();
        });
    }

    /**
     * Consume directly from database (slow path - demonstrates contention).
     * Plain SELECT + business logic + UPDATE with no explicit locking.
     */
    @Transactional
    public ConsumeResponse consumeDirectDb(ConsumeRequest request) {
        return directDbTimer.record(() -> {
            // Query 1: SELECT — read current state
            Optional<DailyLimit> limitOpt = dailyLimitRepository.findByDayDate(request.getDate());

            if (limitOpt.isEmpty()) {
                return ConsumeResponse.builder()
                        .success(false)
                        .transactionId(request.getTransactionId())
                        .date(request.getDate())
                        .source("DATABASE")
                        .message("Date not found")
                        .build();
            }

            DailyLimit limit = limitOpt.get();

            // Business logic: check if sufficient limit
            if (limit.getRemaining() < request.getAmount()) {
                return ConsumeResponse.builder()
                        .success(false)
                        .transactionId(request.getTransactionId())
                        .date(request.getDate())
                        .remainingLimit(limit.getRemaining())
                        .source("DATABASE")
                        .message("Insufficient limit")
                        .build();
            }

            // Compute new values in Java
            limit.setRemaining(limit.getRemaining() - request.getAmount());
            limit.setConsumed(limit.getConsumed() + request.getAmount());
            limit.setTransactionCount(limit.getTransactionCount() + 1);

            // Query 2: UPDATE — JPA flushes the dirty entity
            dailyLimitRepository.save(limit);

            return ConsumeResponse.builder()
                    .success(true)
                    .transactionId(request.getTransactionId() != null ?
                            request.getTransactionId() : UUID.randomUUID().toString())
                    .date(request.getDate())
                    .amountConsumed(request.getAmount())
                    .remainingLimit(limit.getRemaining())
                    .source("DATABASE")
                    .message("Success")
                    .build();
        });
    }

    /**
     * Get limit for a specific date
     */
    public LimitResponse getLimit(LocalDate date) {
        if (cacheService.isCacheEnabled()) {
            Optional<LimitCacheEntry> cached = cacheService.getLimitEntry(date);
            if (cached.isPresent()) {
                LimitCacheEntry entry = cached.get();
                return LimitResponse.builder()
                        .date(date)
                        .initialLimit(entry.getInitialLimit())
                        .remaining(entry.getRemaining())
                        .consumed(entry.getConsumed())
                        .transactionCount(entry.getTransactionCount())
                        .utilizationPercent(entry.getUtilizationPercent())
                        .source("CACHE")
                        .build();
            }
        }
        
        // Fall back to DB
        return dailyLimitRepository.findByDayDate(date)
                .map(limit -> LimitResponse.builder()
                        .date(limit.getDayDate())
                        .initialLimit(limit.getInitialLimit())
                        .remaining(limit.getRemaining())
                        .consumed(limit.getConsumed())
                        .transactionCount(limit.getTransactionCount())
                        .utilizationPercent(limit.getUtilizationPercent())
                        .source("DATABASE")
                        .build())
                .orElse(null);
    }

    /**
     * Get all limits for a month
     */
    public MonthlyLimitsResponse getMonthlyLimits(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1);
        
        List<DailyLimit> limits = dailyLimitRepository.findByMonth(startDate, endDate);
        
        List<LimitResponse> limitResponses = limits.stream()
                .map(limit -> {
                    // Try to get fresh data from cache if enabled
                    if (cacheService.isCacheEnabled()) {
                        Optional<LimitCacheEntry> cached = cacheService.getLimitEntry(limit.getDayDate());
                        if (cached.isPresent()) {
                            LimitCacheEntry entry = cached.get();
                            return LimitResponse.builder()
                                    .date(entry.getDayDate())
                                    .initialLimit(entry.getInitialLimit())
                                    .remaining(entry.getRemaining())
                                    .consumed(entry.getConsumed())
                                    .transactionCount(entry.getTransactionCount())
                                    .utilizationPercent(entry.getUtilizationPercent())
                                    .source("CACHE")
                                    .build();
                        }
                    }
                    
                    return LimitResponse.builder()
                            .date(limit.getDayDate())
                            .initialLimit(limit.getInitialLimit())
                            .remaining(limit.getRemaining())
                            .consumed(limit.getConsumed())
                            .transactionCount(limit.getTransactionCount())
                            .utilizationPercent(limit.getUtilizationPercent())
                            .source("DATABASE")
                            .build();
                })
                .collect(Collectors.toList());
        
        long totalInitial = limitResponses.stream()
                .mapToLong(LimitResponse::getInitialLimit).sum();
        long totalRemaining = limitResponses.stream()
                .mapToLong(LimitResponse::getRemaining).sum();
        long totalConsumed = limitResponses.stream()
                .mapToLong(LimitResponse::getConsumed).sum();
        double avgUtilization = limitResponses.stream()
                .mapToDouble(LimitResponse::getUtilizationPercent).average().orElse(0.0);
        
        return MonthlyLimitsResponse.builder()
                .year(year)
                .month(month)
                .limits(limitResponses)
                .totalInitialLimit(totalInitial)
                .totalRemaining(totalRemaining)
                .totalConsumed(totalConsumed)
                .avgUtilizationPercent(avgUtilization)
                .build();
    }

    /**
     * Reset limits for testing
     */
    @Transactional
    public void resetLimits(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1);
        
        List<DailyLimit> limits = dailyLimitRepository.findByMonth(startDate, endDate);
        
        for (DailyLimit limit : limits) {
            limit.setRemaining(limit.getInitialLimit());
            limit.setConsumed(0L);
            limit.setTransactionCount(0);
            dailyLimitRepository.save(limit);
            
            // Update cache
            if (cacheService.isCacheEnabled()) {
                cacheService.cacheLimit(limit);
            }
        }
        
        log.info("Reset {} limits for {}-{:02d}", limits.size(), year, month);
    }

    /**
     * Reset limits with a large amount for load testing so limits don't get exhausted.
     */
    public void resetLimitsForLoadTest(int year, int month) {
        LocalDate startDate = LocalDate.of(year, month, 1);
        LocalDate endDate = startDate.plusMonths(1);
        long loadTestLimit = 999_999_999L; // ~$10M - won't exhaust during a test

        List<DailyLimit> limits = dailyLimitRepository.findByMonth(startDate, endDate);

        for (DailyLimit limit : limits) {
            limit.setInitialLimit(loadTestLimit);
            limit.setRemaining(loadTestLimit);
            limit.setConsumed(0L);
            limit.setTransactionCount(0);
            dailyLimitRepository.save(limit);

            if (cacheService.isCacheEnabled()) {
                cacheService.cacheLimit(limit);
            }
        }

        log.info("Reset {} limits for load test with {} each", limits.size(), loadTestLimit);
    }

    /**
     * Check if cache is enabled
     */
    public boolean isCacheEnabled() {
        return cacheService.isCacheEnabled();
    }
}
