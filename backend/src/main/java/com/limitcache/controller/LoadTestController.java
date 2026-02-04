package com.limitcache.controller;

import com.limitcache.model.LimitDTOs.*;
import com.limitcache.model.TestHistory;
import com.limitcache.repository.TestHistoryRepository;
import com.limitcache.service.LimitService;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller for running load tests to compare cache vs direct DB performance.
 * This demonstrates the dramatic improvement in throughput and latency.
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Load Test", description = "Performance comparison endpoints")
@CrossOrigin(origins = "*")
public class LoadTestController {

    private final LimitService limitService;
    private final MeterRegistry meterRegistry;
    private final TestHistoryRepository testHistoryRepository;

    @PostMapping("/load-test")
    @Operation(summary = "Run a load test", description = "Simulates concurrent transactions to measure performance")
    public ResponseEntity<ApiResponse<LoadTestResponse>> runLoadTest(
            @RequestBody(required = false) LoadTestRequest request) {
        
        if (request == null) {
            request = LoadTestRequest.builder()
                    .threads(50)
                    .durationSeconds(10)
                    .minAmount(100)
                    .maxAmount(1000)
                    .useCache(true)
                    .build();
        }
        
        log.info("Starting load test: {} threads, {} seconds, useCache: {}", 
                request.getThreads(), request.getDurationSeconds(), request.isUseCache());
        
        LoadTestResponse response = executeLoadTest(request);

        saveLoadTestHistory(response, request.getThreads(), request.getDurationSeconds());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/comparison-test")
    @Operation(summary = "Run comparison test", description = "Runs N requests with cache and without cache, compares time taken")
    public ResponseEntity<ApiResponse<ComparisonResponse>> runComparisonTest(
            @RequestParam(defaultValue = "50") int threads,
            @RequestParam(defaultValue = "50000") int totalRequests) {

        log.info("Starting comparison test: {} threads, {} requests", threads, totalRequests);

        int year = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();

        // Reset limits and warm cache for the cache test
        limitService.resetLimitsForLoadTest(year, month);
        limitService.warmCacheForCurrentMonth();

        // Run cache test
        LoadTestRequest cacheRequest = LoadTestRequest.builder()
                .threads(threads)
                .totalRequests(totalRequests)
                .useCache(true)
                .build();
        LoadTestResponse cacheResults = executeLoadTest(cacheRequest);

        // Reset limits for DB test
        limitService.resetLimitsForLoadTest(year, month);

        // Small delay between tests
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Run DB test with same request count
        LoadTestRequest dbRequest = LoadTestRequest.builder()
                .threads(threads)
                .totalRequests(totalRequests)
                .useCache(false)
                .build();
        LoadTestResponse dbResults = executeLoadTest(dbRequest);

        // Calculate improvement — comparing time taken for same workload
        double timeSpeedup = dbResults.getDurationMs() / Math.max((double) cacheResults.getDurationMs(), 1.0);
        double latencyReduction = 100.0 * (1.0 - (cacheResults.getAvgLatencyMs() /
                Math.max(dbResults.getAvgLatencyMs(), 0.1)));

        ImprovementMetrics improvement = ImprovementMetrics.builder()
                .throughputImprovement(timeSpeedup)
                .latencyReduction(latencyReduction)
                .successRateImprovement(0.0)
                .summary(String.format(
                        "%d requests with %d threads — Cache: %dms, DB: %dms (%.1fx faster)",
                        totalRequests, threads, cacheResults.getDurationMs(), dbResults.getDurationMs(), timeSpeedup))
                .build();

        ComparisonResponse response = ComparisonResponse.builder()
                .cacheResults(cacheResults)
                .directDbResults(dbResults)
                .improvement(improvement)
                .build();

        saveComparisonHistory(cacheResults, dbResults, improvement, threads, 0);

        // Restore normal limits after test
        limitService.resetLimits(year, month);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/quick-test")
    @Operation(summary = "Quick performance test", description = "Runs a quick 5-second test")
    public ResponseEntity<ApiResponse<LoadTestResponse>> quickTest(
            @RequestParam(defaultValue = "true") boolean useCache) {
        
        LoadTestRequest request = LoadTestRequest.builder()
                .threads(20)
                .durationSeconds(5)
                .useCache(useCache)
                .build();
        
        LoadTestResponse response = executeLoadTest(request);

        saveLoadTestHistory(response, request.getThreads(), request.getDurationSeconds());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/history/load-tests")
    @Operation(summary = "Get load test history")
    public ResponseEntity<ApiResponse<List<TestHistory>>> getLoadTestHistory() {
        List<TestHistory> history = testHistoryRepository
                .findTop20ByTestTypeOrderByCreatedAtDesc(TestHistory.TestType.LOAD_TEST);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/history/comparisons")
    @Operation(summary = "Get comparison test history")
    public ResponseEntity<ApiResponse<List<TestHistory>>> getComparisonHistory() {
        List<TestHistory> history = testHistoryRepository
                .findTop20ByTestTypeOrderByCreatedAtDesc(TestHistory.TestType.COMPARISON);
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    /**
     * Execute the actual load test.
     * Supports two modes:
     *   - Time-based (totalRequests == 0): runs for durationSeconds, counts how many requests complete
     *   - Fixed count (totalRequests > 0): runs exactly that many requests, measures total time taken
     */
    private LoadTestResponse executeLoadTest(LoadTestRequest request) {
        int threads = Math.min(request.getThreads(), 200); // Cap at 200 threads
        int durationSeconds = Math.min(request.getDurationSeconds(), 60); // Cap at 60 seconds
        boolean useCache = request.isUseCache();
        int fixedTotal = Math.max(request.getTotalRequests(), 0);
        boolean fixedCountMode = fixedTotal > 0;

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        AtomicInteger completedRequests = new AtomicInteger(0);
        AtomicInteger successfulRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();

        // Shared counter for fixed-count mode: threads decrement until 0
        AtomicInteger remainingRequests = new AtomicInteger(fixedTotal);

        Random random = new Random();
        LocalDate today = LocalDate.now();

        long startTime = System.currentTimeMillis();
        long endTime = startTime + (durationSeconds * 1000L); // only used in time-based mode

        // Submit worker tasks
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                while (true) {
                    // Check termination condition based on mode
                    if (fixedCountMode) {
                        if (remainingRequests.decrementAndGet() < 0) {
                            break; // No more requests to process
                        }
                    } else {
                        if (System.currentTimeMillis() >= endTime) {
                            break;
                        }
                    }

                    try {
                        long amount = request.getMinAmount() +
                                random.nextInt(request.getMaxAmount() - request.getMinAmount());

                        LocalDate targetDate = today;

                        ConsumeRequest consumeRequest = ConsumeRequest.builder()
                                .date(targetDate)
                                .amount(amount)
                                .forceDirectDb(!useCache)
                                .build();

                        long reqStart = System.currentTimeMillis();
                        ConsumeResponse response = limitService.consumeLimit(consumeRequest);
                        long latency = System.currentTimeMillis() - reqStart;

                        completedRequests.incrementAndGet();
                        totalLatency.addAndGet(latency);
                        latencies.add(latency);

                        if (response.isSuccess()) {
                            successfulRequests.incrementAndGet();
                        } else {
                            failedRequests.incrementAndGet();
                        }

                    } catch (Exception e) {
                        completedRequests.incrementAndGet();
                        failedRequests.incrementAndGet();
                    }
                }
            }));
        }

        // Wait for completion
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                log.warn("Worker thread error: {}", e.getMessage());
            }
        }

        executor.shutdown();

        long actualDuration = System.currentTimeMillis() - startTime;

        // Calculate percentiles
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);

        double p95 = 0, p99 = 0, avg = 0;
        if (!sortedLatencies.isEmpty()) {
            avg = totalLatency.get() / (double) sortedLatencies.size();
            int p95Index = (int) (sortedLatencies.size() * 0.95);
            int p99Index = (int) (sortedLatencies.size() * 0.99);
            p95 = sortedLatencies.get(Math.min(p95Index, sortedLatencies.size() - 1));
            p99 = sortedLatencies.get(Math.min(p99Index, sortedLatencies.size() - 1));
        }

        double throughput = completedRequests.get() * 1000.0 / actualDuration;

        log.info("Load test completed [{}]: {} requests, {} success, {} failed, {:.2f} TPS, {:.2f}ms avg latency, {}ms duration",
                fixedCountMode ? "FIXED_COUNT" : "TIME_BASED",
                completedRequests.get(), successfulRequests.get(), failedRequests.get(),
                throughput, avg, actualDuration);

        return LoadTestResponse.builder()
                .completed(true)
                .totalRequests(completedRequests.get())
                .successfulRequests(successfulRequests.get())
                .failedRequests(failedRequests.get())
                .avgLatencyMs(avg)
                .p95LatencyMs(p95)
                .p99LatencyMs(p99)
                .throughputTps(throughput)
                .durationMs(actualDuration)
                .mode(useCache ? "CACHE_ENABLED" : "DIRECT_DB")
                .message(String.format("Completed %d requests in %dms",
                        completedRequests.get(), actualDuration))
                .build();
    }

    private void saveLoadTestHistory(LoadTestResponse response, int threads, int durationSeconds) {
        try {
            TestHistory history = TestHistory.builder()
                    .testType(TestHistory.TestType.LOAD_TEST)
                    .totalRequests(response.getTotalRequests())
                    .successfulRequests(response.getSuccessfulRequests())
                    .failedRequests(response.getFailedRequests())
                    .avgLatencyMs(response.getAvgLatencyMs())
                    .p95LatencyMs(response.getP95LatencyMs())
                    .p99LatencyMs(response.getP99LatencyMs())
                    .throughputTps(response.getThroughputTps())
                    .durationMs(response.getDurationMs())
                    .mode(response.getMode())
                    .threads(threads)
                    .durationSeconds(durationSeconds)
                    .build();
            testHistoryRepository.save(history);
        } catch (Exception e) {
            log.warn("Failed to save load test history: {}", e.getMessage());
        }
    }

    private void saveComparisonHistory(LoadTestResponse cacheResults, LoadTestResponse dbResults,
                                       ImprovementMetrics improvement, int threads, int durationSeconds) {
        try {
            TestHistory history = TestHistory.builder()
                    .testType(TestHistory.TestType.COMPARISON)
                    .totalRequests(cacheResults.getTotalRequests())
                    .successfulRequests(cacheResults.getSuccessfulRequests())
                    .failedRequests(cacheResults.getFailedRequests())
                    .avgLatencyMs(cacheResults.getAvgLatencyMs())
                    .p95LatencyMs(cacheResults.getP95LatencyMs())
                    .p99LatencyMs(cacheResults.getP99LatencyMs())
                    .throughputTps(cacheResults.getThroughputTps())
                    .durationMs(cacheResults.getDurationMs())
                    .mode(cacheResults.getMode())
                    .threads(threads)
                    .durationSeconds(durationSeconds)
                    .dbTotalRequests(dbResults.getTotalRequests())
                    .dbSuccessfulRequests(dbResults.getSuccessfulRequests())
                    .dbFailedRequests(dbResults.getFailedRequests())
                    .dbAvgLatencyMs(dbResults.getAvgLatencyMs())
                    .dbP95LatencyMs(dbResults.getP95LatencyMs())
                    .dbP99LatencyMs(dbResults.getP99LatencyMs())
                    .dbThroughputTps(dbResults.getThroughputTps())
                    .dbDurationMs(dbResults.getDurationMs())
                    .throughputImprovement(improvement.getThroughputImprovement())
                    .latencyReduction(improvement.getLatencyReduction())
                    .successRateImprovement(improvement.getSuccessRateImprovement())
                    .improvementSummary(improvement.getSummary())
                    .build();
            testHistoryRepository.save(history);
        } catch (Exception e) {
            log.warn("Failed to save comparison history: {}", e.getMessage());
        }
    }
}
