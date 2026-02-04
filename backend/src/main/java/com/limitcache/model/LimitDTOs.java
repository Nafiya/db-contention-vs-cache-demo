package com.limitcache.model;

import lombok.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;

/**
 * Request/Response DTOs for the Limit Cache API
 */
public class LimitDTOs {

    // ==========================================
    // Request DTOs
    // ==========================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumeRequest {
        private LocalDate date;
        private Long amount;
        private String transactionId;
        private boolean forceDirectDb;  // For comparison testing
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BatchConsumeRequest {
        private List<ConsumeRequest> transactions;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoadTestRequest {
        @Builder.Default
        private int threads = 50;
        @Builder.Default
        private int durationSeconds = 30;
        @Builder.Default
        private int totalRequests = 0; // 0 = time-based mode, >0 = fixed request count mode
        @Builder.Default
        private int minAmount = 100;
        @Builder.Default
        private int maxAmount = 1000;
        @Builder.Default
        private boolean useCache = true;
    }

    // ==========================================
    // Response DTOs
    // ==========================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConsumeResponse {
        private boolean success;
        private String transactionId;
        private LocalDate date;
        private Long amountConsumed;
        private Long remainingLimit;
        private String source;  // "CACHE" or "DATABASE"
        private Long latencyMs;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LimitResponse {
        private LocalDate date;
        private Long initialLimit;
        private Long remaining;
        private Long consumed;
        private Integer transactionCount;
        private Double utilizationPercent;
        private String source;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyLimitsResponse {
        private int year;
        private int month;
        private List<LimitResponse> limits;
        private Long totalInitialLimit;
        private Long totalRemaining;
        private Long totalConsumed;
        private Double avgUtilizationPercent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncResponse {
        private boolean success;
        private int recordsSynced;
        private Long durationMs;
        private String message;
        private Instant syncedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoadTestResponse {
        private boolean completed;
        private int totalRequests;
        private int successfulRequests;
        private int failedRequests;
        private Double avgLatencyMs;
        private Double p95LatencyMs;
        private Double p99LatencyMs;
        private Double throughputTps;
        private Long durationMs;
        private String mode;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetricsResponse {
        private Instant timestamp;
        private CacheMetrics cacheMetrics;
        private DatabaseMetrics databaseMetrics;
        private SyncMetrics syncMetrics;
        private SystemMetrics systemMetrics;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheMetrics {
        private boolean enabled;
        private Long totalKeys;
        private Long hitCount;
        private Long missCount;
        private Double hitRatio;
        private Long memoryUsedBytes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatabaseMetrics {
        private Integer activeConnections;
        private Integer idleConnections;
        private Integer pendingConnections;
        private Long totalQueries;
        private Double avgQueryTimeMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncMetrics {
        private boolean enabled;
        private Integer intervalSeconds;
        private Long lastSyncDurationMs;
        private Instant lastSyncTime;
        private Integer recordsSyncedLastRun;
        private Long totalSyncs;
        private Long totalRecordsSynced;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SystemMetrics {
        private Double cpuUsagePercent;
        private Long memoryUsedBytes;
        private Long memoryMaxBytes;
        private Integer activeThreads;
    }

    // ==========================================
    // Comparison Response (Cache vs Direct DB)
    // ==========================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComparisonResponse {
        private LoadTestResponse cacheResults;
        private LoadTestResponse directDbResults;
        private ImprovementMetrics improvement;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImprovementMetrics {
        private Double throughputImprovement;  // e.g., "100x"
        private Double latencyReduction;       // e.g., "95%"
        private Double successRateImprovement;
        private String summary;
    }

    // ==========================================
    // Live Demo SSE Event
    // ==========================================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LiveDemoEvent {
        private String phase;           // "CACHE" or "DB"
        private String eventType;       // "tick", "phase_start", "phase_end", "demo_complete"
        private int completedRequests;
        private int totalTargetRequests;
        private int failedRequests;
        private double avgLatencyMs;
        private double currentTps;
        private long elapsedMs;
        private long lastRequestLatencyMs;
        private int activeThreads;
        private int queueDepth;
        private String message;
        // PostgreSQL contention metrics
        private int pgActiveQueries;
        private int pgWaitingOnLocks;
        private int pgIdleInTransaction;
        // Redis metrics
        private long redisOpsPerSec;
        private int redisConnectedClients;
        private String redisUsedMemory;
        private long redisKeyspaceHits;
        private long redisKeyspaceMisses;
    }

    // ==========================================
    // Generic API Response Wrapper
    // ==========================================

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiResponse<T> {
        private boolean success;
        private T data;
        private String message;
        private String error;
        private Instant timestamp;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setSuccess(true);
            response.setData(data);
            response.setTimestamp(Instant.now());
            return response;
        }

        public static <T> ApiResponse<T> success(T data, String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setSuccess(true);
            response.setData(data);
            response.setMessage(message);
            response.setTimestamp(Instant.now());
            return response;
        }

        public static <T> ApiResponse<T> error(String error) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setSuccess(false);
            response.setError(error);
            response.setTimestamp(Instant.now());
            return response;
        }
    }
}
