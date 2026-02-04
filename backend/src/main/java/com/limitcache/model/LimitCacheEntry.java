package com.limitcache.model;

import lombok.*;
import java.io.Serializable;
import java.time.LocalDate;

/**
 * Cache entry representing a daily limit in Redis.
 * Stored as a Hash for efficient field-level operations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LimitCacheEntry implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private LocalDate dayDate;
    private Long initialLimit;
    private Long remaining;
    private Long consumed;
    private Integer transactionCount;
    private Integer version;
    
    /**
     * Check if this entry has been modified (dirty)
     */
    @Builder.Default
    private boolean dirty = false;
    
    /**
     * Last modification timestamp in cache
     */
    private Long lastModifiedMs;
    
    /**
     * Check if limit has sufficient remaining amount
     */
    public boolean hasSufficientLimit(long amount) {
        return this.remaining != null && this.remaining >= amount;
    }
    
    /**
     * Consume limit atomically (called after Redis DECRBY)
     */
    public void recordConsumption(long amount, long newRemaining) {
        this.remaining = newRemaining;
        this.consumed = this.initialLimit - newRemaining;
        this.transactionCount++;
        this.dirty = true;
        this.lastModifiedMs = System.currentTimeMillis();
    }
    
    /**
     * Mark as synced to DB
     */
    public void markSynced() {
        this.dirty = false;
    }
    
    /**
     * Get utilization percentage
     */
    public double getUtilizationPercent() {
        if (initialLimit == null || initialLimit == 0) return 0.0;
        return ((initialLimit - remaining) * 100.0) / initialLimit;
    }
    
    /**
     * Generate Redis hash key
     */
    public static String generateKey(String prefix, LocalDate date) {
        return String.format("%s:%d:%02d", prefix, date.getYear(), date.getMonthValue());
    }
    
    /**
     * Generate Redis hash field name
     */
    public static String generateField(LocalDate date) {
        return String.format("day_%02d", date.getDayOfMonth());
    }
}
