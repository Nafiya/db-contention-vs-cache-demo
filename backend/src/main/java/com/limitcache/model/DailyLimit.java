package com.limitcache.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.Instant;

/**
 * Entity representing daily transaction limits.
 * This is the "hot table" that causes DB contention under high load.
 */
@Entity
@Table(name = "daily_limits")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DailyLimit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "day_date", nullable = false, unique = true)
    private LocalDate dayDate;

    @Column(name = "initial_limit", nullable = false)
    private Long initialLimit;

    @Column(name = "remaining", nullable = false)
    private Long remaining;

    @Column(name = "consumed", nullable = false)
    @Builder.Default
    private Long consumed = 0L;

    @Column(name = "transaction_count", nullable = false)
    @Builder.Default
    private Integer transactionCount = 0;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Check if limit has sufficient remaining amount
     */
    public boolean hasSufficientLimit(long amount) {
        return this.remaining >= amount;
    }

    /**
     * Consume limit (deduct amount)
     * @return true if successful, false if insufficient
     */
    public boolean consume(long amount) {
        if (!hasSufficientLimit(amount)) {
            return false;
        }
        this.remaining -= amount;
        this.consumed += amount;
        this.transactionCount++;
        return true;
    }

    /**
     * Get utilization percentage
     */
    public double getUtilizationPercent() {
        if (initialLimit == 0) return 0.0;
        return (consumed * 100.0) / initialLimit;
    }

    /**
     * Convert to cache-friendly DTO
     */
    public LimitCacheEntry toCacheEntry() {
        return LimitCacheEntry.builder()
                .dayDate(this.dayDate)
                .initialLimit(this.initialLimit)
                .remaining(this.remaining)
                .consumed(this.consumed)
                .transactionCount(this.transactionCount)
                .version(this.version)
                .build();
    }
}
