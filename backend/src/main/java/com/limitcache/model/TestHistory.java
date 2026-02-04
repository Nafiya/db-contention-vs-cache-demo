package com.limitcache.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import java.time.Instant;

@Entity
@Table(name = "test_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "test_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private TestType testType;

    // Primary results (single run for LOAD_TEST, cache run for COMPARISON)
    @Column(name = "total_requests", nullable = false)
    private Integer totalRequests;

    @Column(name = "successful_requests", nullable = false)
    private Integer successfulRequests;

    @Column(name = "failed_requests", nullable = false)
    private Integer failedRequests;

    @Column(name = "avg_latency_ms")
    private Double avgLatencyMs;

    @Column(name = "p95_latency_ms")
    private Double p95LatencyMs;

    @Column(name = "p99_latency_ms")
    private Double p99LatencyMs;

    @Column(name = "throughput_tps")
    private Double throughputTps;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "mode", length = 30)
    private String mode;

    // Test configuration
    @Column(name = "threads")
    private Integer threads;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    // Comparison-only: direct DB results
    @Column(name = "db_total_requests")
    private Integer dbTotalRequests;

    @Column(name = "db_successful_requests")
    private Integer dbSuccessfulRequests;

    @Column(name = "db_failed_requests")
    private Integer dbFailedRequests;

    @Column(name = "db_avg_latency_ms")
    private Double dbAvgLatencyMs;

    @Column(name = "db_p95_latency_ms")
    private Double dbP95LatencyMs;

    @Column(name = "db_p99_latency_ms")
    private Double dbP99LatencyMs;

    @Column(name = "db_throughput_tps")
    private Double dbThroughputTps;

    @Column(name = "db_duration_ms")
    private Long dbDurationMs;

    // Comparison-only: improvement metrics
    @Column(name = "throughput_improvement")
    private Double throughputImprovement;

    @Column(name = "latency_reduction")
    private Double latencyReduction;

    @Column(name = "success_rate_improvement")
    private Double successRateImprovement;

    @Column(name = "improvement_summary", columnDefinition = "TEXT")
    private String improvementSummary;

    @CreationTimestamp
    @Column(name = "created_at")
    private Instant createdAt;

    public enum TestType {
        LOAD_TEST,
        COMPARISON
    }
}
