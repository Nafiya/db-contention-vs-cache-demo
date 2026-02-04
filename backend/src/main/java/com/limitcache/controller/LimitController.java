package com.limitcache.controller;

import com.limitcache.model.LimitDTOs.*;
import com.limitcache.service.CacheSyncService;
import com.limitcache.service.LimitCacheService;
import com.limitcache.service.LimitService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/limits")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Limits", description = "Daily limit operations")
@CrossOrigin(origins = "*")
public class LimitController {

    private final LimitService limitService;
    private final LimitCacheService cacheService;
    private final CacheSyncService syncService;

    // ==========================================
    // Limit Query Endpoints
    // ==========================================

    @GetMapping("/{year}/{month}")
    @Operation(summary = "Get all limits for a month")
    public ResponseEntity<ApiResponse<MonthlyLimitsResponse>> getMonthlyLimits(
            @PathVariable int year,
            @PathVariable int month) {
        
        MonthlyLimitsResponse response = limitService.getMonthlyLimits(year, month);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{year}/{month}/{day}")
    @Operation(summary = "Get limit for a specific day")
    public ResponseEntity<ApiResponse<LimitResponse>> getDailyLimit(
            @PathVariable int year,
            @PathVariable int month,
            @PathVariable int day) {
        
        LocalDate date = LocalDate.of(year, month, day);
        LimitResponse response = limitService.getLimit(date);
        
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/today")
    @Operation(summary = "Get today's limit")
    public ResponseEntity<ApiResponse<LimitResponse>> getTodayLimit() {
        LimitResponse response = limitService.getLimit(LocalDate.now());
        
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ==========================================
    // Limit Consumption Endpoints
    // ==========================================

    @PostMapping("/consume")
    @Operation(summary = "Consume limit (transaction)")
    public ResponseEntity<ApiResponse<ConsumeResponse>> consumeLimit(
            @RequestBody ConsumeRequest request) {
        
        // Default to today if date not specified
        if (request.getDate() == null) {
            request.setDate(LocalDate.now());
        }
        
        // Generate transaction ID if not provided
        if (request.getTransactionId() == null) {
            request.setTransactionId(UUID.randomUUID().toString());
        }
        
        ConsumeResponse response = limitService.consumeLimit(request);
        
        if (response.isSuccess()) {
            return ResponseEntity.ok(ApiResponse.success(response));
        } else {
            return ResponseEntity.ok(ApiResponse.success(response, response.getMessage()));
        }
    }

    @PostMapping("/consume/batch")
    @Operation(summary = "Consume limits in batch")
    public ResponseEntity<ApiResponse<BatchConsumeResponse>> consumeBatch(
            @RequestBody BatchConsumeRequest request) {
        
        int success = 0;
        int failed = 0;
        
        for (ConsumeRequest txn : request.getTransactions()) {
            ConsumeResponse response = limitService.consumeLimit(txn);
            if (response.isSuccess()) {
                success++;
            } else {
                failed++;
            }
        }
        
        BatchConsumeResponse response = BatchConsumeResponse.builder()
                .totalRequests(request.getTransactions().size())
                .successCount(success)
                .failedCount(failed)
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // ==========================================
    // Cache Management Endpoints
    // ==========================================

    @PostMapping("/cache/warm")
    @Operation(summary = "Warm cache for a month")
    public ResponseEntity<ApiResponse<Map<String, Object>>> warmCache(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month) {
        
        if (year == 0) year = LocalDate.now().getYear();
        if (month == 0) month = LocalDate.now().getMonthValue();
        
        int count = cacheService.warmCache(year, month);
        
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "year", year,
                "month", month,
                "recordsCached", count
        ), "Cache warmed successfully"));
    }

    @PostMapping("/cache/clear")
    @Operation(summary = "Clear all cache entries")
    public ResponseEntity<ApiResponse<String>> clearCache() {
        cacheService.clearCache();
        return ResponseEntity.ok(ApiResponse.success("Cache cleared", "Cache cleared successfully"));
    }

    @GetMapping("/cache/stats")
    @Operation(summary = "Get cache statistics")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCacheStats() {
        Map<String, Object> stats = cacheService.getCacheStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ==========================================
    // Sync Endpoints
    // ==========================================

    @PostMapping("/sync")
    @Operation(summary = "Manually trigger cache-to-DB sync")
    public ResponseEntity<ApiResponse<SyncResponse>> triggerSync() {
        CacheSyncService.SyncResult result = syncService.manualSync();
        
        SyncResponse response = SyncResponse.builder()
                .success(result.success())
                .recordsSynced(result.recordsSynced())
                .durationMs(result.durationMs())
                .message(result.message())
                .syncedAt(Instant.now())
                .build();
        
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/sync/stats")
    @Operation(summary = "Get sync statistics")
    public ResponseEntity<ApiResponse<CacheSyncService.SyncStats>> getSyncStats() {
        CacheSyncService.SyncStats stats = syncService.getSyncStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ==========================================
    // Admin Endpoints
    // ==========================================

    @PostMapping("/reset")
    @Operation(summary = "Reset limits for testing")
    public ResponseEntity<ApiResponse<String>> resetLimits(
            @RequestParam(defaultValue = "0") int year,
            @RequestParam(defaultValue = "0") int month) {
        
        if (year == 0) year = LocalDate.now().getYear();
        if (month == 0) month = LocalDate.now().getMonthValue();
        
        limitService.resetLimits(year, month);
        
        return ResponseEntity.ok(ApiResponse.success(
                String.format("Limits reset for %d-%02d", year, month),
                "Limits reset successfully"));
    }

    @GetMapping("/status")
    @Operation(summary = "Get system status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus() {
        Map<String, Object> status = Map.of(
                "cacheEnabled", cacheService.isCacheEnabled(),
                "syncHealthy", syncService.isSyncHealthy(),
                "timestamp", Instant.now()
        );
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    // ==========================================
    // Helper DTOs
    // ==========================================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BatchConsumeResponse {
        private int totalRequests;
        private int successCount;
        private int failedCount;
    }
}
