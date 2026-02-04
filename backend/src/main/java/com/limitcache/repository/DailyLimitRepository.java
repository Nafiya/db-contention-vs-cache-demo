package com.limitcache.repository;

import com.limitcache.model.DailyLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyLimitRepository extends JpaRepository<DailyLimit, Long> {

    /**
     * Find limit by date
     */
    Optional<DailyLimit> findByDayDate(LocalDate dayDate);

    /**
     * Find limit by date with pessimistic write lock (SELECT FOR UPDATE).
     * Threads queue up waiting for the row lock, ensuring all updates succeed
     * but demonstrating serialization overhead.
     */
    @Query("SELECT d FROM DailyLimit d WHERE d.dayDate = :dayDate")
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<DailyLimit> findByDayDateForUpdate(@Param("dayDate") LocalDate dayDate);

    /**
     * Find all limits for a specific month
     */
    @Query("SELECT d FROM DailyLimit d WHERE " +
           "d.dayDate >= :startDate AND d.dayDate < :endDate " +
           "ORDER BY d.dayDate")
    List<DailyLimit> findByMonth(@Param("startDate") LocalDate startDate, 
                                  @Param("endDate") LocalDate endDate);

    /**
     * Find limits for date range
     */
    List<DailyLimit> findByDayDateBetweenOrderByDayDate(LocalDate startDate, LocalDate endDate);

    /**
     * Direct update with optimistic locking (demonstrates the contention problem)
     */
    @Transactional
    @Modifying
    @Query("UPDATE DailyLimit d SET " +
           "d.remaining = d.remaining - :amount, " +
           "d.consumed = d.consumed + :amount, " +
           "d.transactionCount = d.transactionCount + 1, " +
           "d.version = d.version + 1 " +
           "WHERE d.dayDate = :dayDate " +
           "AND d.remaining >= :amount " +
           "AND d.version = :expectedVersion")
    int consumeLimitWithLock(@Param("dayDate") LocalDate dayDate,
                             @Param("amount") Long amount,
                             @Param("expectedVersion") Integer expectedVersion);

    /**
     * Batch update for cache sync (no locking needed - cache is source of truth)
     */
    @Transactional
    @Modifying
    @Query("UPDATE DailyLimit d SET " +
           "d.remaining = :remaining, " +
           "d.consumed = :consumed, " +
           "d.transactionCount = :transactionCount, " +
           "d.version = d.version + 1 " +
           "WHERE d.dayDate = :dayDate")
    int syncFromCache(@Param("dayDate") LocalDate dayDate,
                      @Param("remaining") Long remaining,
                      @Param("consumed") Long consumed,
                      @Param("transactionCount") Integer transactionCount);

    /**
     * Get total remaining for month
     */
    @Query("SELECT SUM(d.remaining) FROM DailyLimit d WHERE " +
           "d.dayDate >= :startDate AND d.dayDate < :endDate")
    Long sumRemainingByMonth(@Param("startDate") LocalDate startDate,
                             @Param("endDate") LocalDate endDate);

    /**
     * Find limits with low remaining (for alerts)
     */
    @Query("SELECT d FROM DailyLimit d WHERE " +
           "d.dayDate >= CURRENT_DATE AND " +
           "(d.remaining * 1.0 / d.initialLimit) < :threshold " +
           "ORDER BY d.dayDate")
    List<DailyLimit> findLowLimits(@Param("threshold") double threshold);

    /**
     * Check if date exists
     */
    boolean existsByDayDate(LocalDate dayDate);

    /**
     * Count transactions for today
     */
    @Query("SELECT d.transactionCount FROM DailyLimit d WHERE d.dayDate = CURRENT_DATE")
    Optional<Integer> getTodayTransactionCount();
}
