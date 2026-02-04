-- ===========================================
-- Limit Cache POC - Indexes
-- ===========================================

-- Primary access pattern: lookup by date
CREATE INDEX IF NOT EXISTS idx_daily_limits_day_date 
ON daily_limits(day_date);

-- For querying by date range (monthly views)
CREATE INDEX IF NOT EXISTS idx_daily_limits_date_range 
ON daily_limits(day_date) 
INCLUDE (remaining, consumed, transaction_count);

-- Transaction log indexes
CREATE INDEX IF NOT EXISTS idx_transaction_log_day_date 
ON transaction_log(day_date);

CREATE INDEX IF NOT EXISTS idx_transaction_log_created_at 
ON transaction_log(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_transaction_log_status 
ON transaction_log(status) 
WHERE status != 'SUCCESS';

-- Sync history indexes
CREATE INDEX IF NOT EXISTS idx_sync_history_started_at 
ON sync_history(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_sync_history_status 
ON sync_history(status);

-- Performance metrics indexes
CREATE INDEX IF NOT EXISTS idx_performance_metrics_time 
ON performance_metrics(metric_time DESC);

CREATE INDEX IF NOT EXISTS idx_performance_metrics_mode_time 
ON performance_metrics(mode, metric_time DESC);

-- Analyze tables for query planner
ANALYZE daily_limits;
ANALYZE transaction_log;
ANALYZE sync_history;
ANALYZE performance_metrics;

-- Display index information
SELECT 
    schemaname,
    tablename,
    indexname,
    pg_size_pretty(pg_relation_size(indexrelid)) as index_size
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;
