package com.limitcache.repository;

import com.limitcache.model.SyncHistory;
import com.limitcache.model.SyncHistory.SyncStatus;
import com.limitcache.model.SyncHistory.SyncType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {

    /**
     * Find latest sync
     */
    Optional<SyncHistory> findTopByOrderByStartedAtDesc();

    /**
     * Find latest successful sync
     */
    Optional<SyncHistory> findTopByStatusOrderByStartedAtDesc(SyncStatus status);

    /**
     * Find syncs in time range
     */
    List<SyncHistory> findByStartedAtBetweenOrderByStartedAtDesc(Instant start, Instant end);

    /**
     * Count syncs by status in last N hours
     */
    @Query("SELECT COUNT(s) FROM SyncHistory s WHERE " +
           "s.status = :status AND s.startedAt >= :since")
    long countByStatusSince(@Param("status") SyncStatus status, 
                            @Param("since") Instant since);

    /**
     * Get average sync duration
     */
    @Query("SELECT AVG(s.durationMs) FROM SyncHistory s WHERE " +
           "s.status = 'SUCCESS' AND s.startedAt >= :since")
    Double getAvgDurationSince(@Param("since") Instant since);

    /**
     * Get total records synced
     */
    @Query("SELECT SUM(s.recordsSynced) FROM SyncHistory s WHERE " +
           "s.status IN ('SUCCESS', 'PARTIAL') AND s.startedAt >= :since")
    Long getTotalRecordsSyncedSince(@Param("since") Instant since);

    /**
     * Find failed syncs
     */
    List<SyncHistory> findByStatusAndStartedAtAfterOrderByStartedAtDesc(
            SyncStatus status, Instant since);

    /**
     * Count by type
     */
    long countBySyncType(SyncType syncType);
}
