package com.limitcache.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

/**
 * Entity tracking cache-to-database synchronization history.
 */
@Entity
@Table(name = "sync_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    private SyncType syncType;

    @Column(name = "records_synced", nullable = false)
    @Builder.Default
    private Integer recordsSynced = 0;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SyncStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public enum SyncType {
        SCHEDULED,
        MANUAL,
        STARTUP,
        SHUTDOWN
    }

    public enum SyncStatus {
        SUCCESS,
        PARTIAL,
        FAILED
    }

    /**
     * Create a new sync history entry at start
     */
    public static SyncHistory start(SyncType type) {
        return SyncHistory.builder()
                .syncType(type)
                .startedAt(Instant.now())
                .status(SyncStatus.SUCCESS)
                .build();
    }

    /**
     * Mark sync as completed
     */
    public void complete(int recordsSynced) {
        this.completedAt = Instant.now();
        this.recordsSynced = recordsSynced;
        this.durationMs = (int) (completedAt.toEpochMilli() - startedAt.toEpochMilli());
        this.status = SyncStatus.SUCCESS;
    }

    /**
     * Mark sync as failed
     */
    public void fail(String errorMessage) {
        this.completedAt = Instant.now();
        this.durationMs = (int) (completedAt.toEpochMilli() - startedAt.toEpochMilli());
        this.status = SyncStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /**
     * Mark sync as partial (some records synced)
     */
    public void partial(int recordsSynced, String errorMessage) {
        this.completedAt = Instant.now();
        this.recordsSynced = recordsSynced;
        this.durationMs = (int) (completedAt.toEpochMilli() - startedAt.toEpochMilli());
        this.status = SyncStatus.PARTIAL;
        this.errorMessage = errorMessage;
    }
}
