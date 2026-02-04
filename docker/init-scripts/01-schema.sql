-- ===========================================
-- Limit Cache POC - Database Schema
-- ===========================================

-- Enable extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ===========================================
-- Main table: Daily Limits
-- ===========================================
-- This table stores the spending/transaction limits for each day
-- In production, this would be per-account, but for POC we use global limits

CREATE TABLE daily_limits (
    id              BIGSERIAL PRIMARY KEY,
    day_date        DATE NOT NULL UNIQUE,
    initial_limit   BIGINT NOT NULL DEFAULT 1000000,      -- Initial limit (e.g., $10,000.00 in cents)
    remaining       BIGINT NOT NULL DEFAULT 1000000,      -- Current remaining limit
    consumed        BIGINT NOT NULL DEFAULT 0,            -- Total consumed
    transaction_count INTEGER NOT NULL DEFAULT 0,         -- Number of transactions
    version         INTEGER NOT NULL DEFAULT 0,           -- Optimistic locking
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    -- Constraints
    CONSTRAINT chk_remaining_non_negative CHECK (remaining >= 0),
    CONSTRAINT chk_consumed_non_negative CHECK (consumed >= 0),
    CONSTRAINT chk_limit_balance CHECK (initial_limit = remaining + consumed)
);

-- ===========================================
-- Audit table: Sync History
-- ===========================================
-- Tracks when cache was synced to DB

CREATE TABLE sync_history (
    id              BIGSERIAL PRIMARY KEY,
    sync_type       VARCHAR(50) NOT NULL,                 -- 'SCHEDULED', 'MANUAL', 'STARTUP'
    records_synced  INTEGER NOT NULL DEFAULT 0,
    duration_ms     INTEGER,
    status          VARCHAR(20) NOT NULL,                 -- 'SUCCESS', 'PARTIAL', 'FAILED'
    error_message   TEXT,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE,
    
    -- Index for querying recent syncs
    CONSTRAINT chk_sync_status CHECK (status IN ('SUCCESS', 'PARTIAL', 'FAILED'))
);

-- ===========================================
-- Audit table: Transaction Log
-- ===========================================
-- Logs all limit consumption for debugging/reconciliation

CREATE TABLE transaction_log (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  UUID NOT NULL DEFAULT uuid_generate_v4(),
    day_date        DATE NOT NULL,
    amount          BIGINT NOT NULL,
    source          VARCHAR(20) NOT NULL,                 -- 'CACHE', 'DATABASE'
    status          VARCHAR(20) NOT NULL,                 -- 'SUCCESS', 'INSUFFICIENT', 'ERROR'
    remaining_after BIGINT,
    latency_ms      INTEGER,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    
    CONSTRAINT chk_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_txn_status CHECK (status IN ('SUCCESS', 'INSUFFICIENT', 'ERROR'))
);

-- ===========================================
-- Metrics table: Performance Snapshots
-- ===========================================
-- Stores periodic performance metrics for comparison

CREATE TABLE performance_metrics (
    id              BIGSERIAL PRIMARY KEY,
    metric_time     TIMESTAMP WITH TIME ZONE NOT NULL,
    mode            VARCHAR(20) NOT NULL,                 -- 'CACHE_ENABLED', 'DIRECT_DB'
    requests_total  BIGINT NOT NULL DEFAULT 0,
    requests_success BIGINT NOT NULL DEFAULT 0,
    requests_failed BIGINT NOT NULL DEFAULT 0,
    avg_latency_ms  DECIMAL(10,2),
    p95_latency_ms  DECIMAL(10,2),
    p99_latency_ms  DECIMAL(10,2),
    throughput_tps  DECIMAL(10,2),
    db_cpu_percent  DECIMAL(5,2),
    cache_hit_ratio DECIMAL(5,4),
    
    CONSTRAINT chk_mode CHECK (mode IN ('CACHE_ENABLED', 'DIRECT_DB'))
);

-- ===========================================
-- Function: Update timestamp trigger
-- ===========================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

-- Apply trigger to daily_limits
CREATE TRIGGER update_daily_limits_updated_at
    BEFORE UPDATE ON daily_limits
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ===========================================
-- Function: Consume limit (for direct DB mode)
-- ===========================================
-- This function demonstrates the contention problem

CREATE OR REPLACE FUNCTION consume_limit_direct(
    p_day_date DATE,
    p_amount BIGINT
) RETURNS TABLE (
    success BOOLEAN,
    new_remaining BIGINT,
    message TEXT
) AS $$
DECLARE
    v_remaining BIGINT;
    v_version INTEGER;
BEGIN
    -- This SELECT FOR UPDATE causes the serialization problem
    SELECT remaining, version INTO v_remaining, v_version
    FROM daily_limits
    WHERE day_date = p_day_date
    FOR UPDATE;  -- Row-level lock!
    
    IF NOT FOUND THEN
        RETURN QUERY SELECT FALSE, 0::BIGINT, 'Day not found'::TEXT;
        RETURN;
    END IF;
    
    IF v_remaining < p_amount THEN
        RETURN QUERY SELECT FALSE, v_remaining, 'Insufficient limit'::TEXT;
        RETURN;
    END IF;
    
    -- Update the limit
    UPDATE daily_limits
    SET remaining = remaining - p_amount,
        consumed = consumed + p_amount,
        transaction_count = transaction_count + 1,
        version = version + 1
    WHERE day_date = p_day_date AND version = v_version;
    
    IF NOT FOUND THEN
        -- Optimistic lock failed
        RETURN QUERY SELECT FALSE, v_remaining, 'Concurrent modification'::TEXT;
        RETURN;
    END IF;
    
    RETURN QUERY SELECT TRUE, (v_remaining - p_amount)::BIGINT, 'Success'::TEXT;
END;
$$ LANGUAGE plpgsql;

-- ===========================================
-- Function: Batch sync from cache
-- ===========================================
-- Efficient batch update for cache sync

CREATE OR REPLACE FUNCTION sync_limits_batch(
    p_updates JSONB
) RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER := 0;
    v_record JSONB;
BEGIN
    FOR v_record IN SELECT * FROM jsonb_array_elements(p_updates)
    LOOP
        UPDATE daily_limits
        SET remaining = (v_record->>'remaining')::BIGINT,
            consumed = (v_record->>'consumed')::BIGINT,
            transaction_count = (v_record->>'transaction_count')::INTEGER,
            version = version + 1
        WHERE day_date = (v_record->>'day_date')::DATE;
        
        IF FOUND THEN
            v_count := v_count + 1;
        END IF;
    END LOOP;
    
    RETURN v_count;
END;
$$ LANGUAGE plpgsql;

-- ===========================================
-- View: Current month limits summary
-- ===========================================

CREATE OR REPLACE VIEW v_current_month_limits AS
SELECT 
    day_date,
    initial_limit,
    remaining,
    consumed,
    transaction_count,
    ROUND((consumed::DECIMAL / NULLIF(initial_limit, 0)) * 100, 2) as utilization_percent,
    version,
    updated_at
FROM daily_limits
WHERE day_date >= DATE_TRUNC('month', CURRENT_DATE)
  AND day_date < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'
ORDER BY day_date;

-- ===========================================
-- View: Sync statistics
-- ===========================================

CREATE OR REPLACE VIEW v_sync_stats AS
SELECT 
    DATE_TRUNC('hour', started_at) as hour,
    COUNT(*) as sync_count,
    SUM(records_synced) as total_records,
    AVG(duration_ms)::INTEGER as avg_duration_ms,
    SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) as success_count,
    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) as failed_count
FROM sync_history
WHERE started_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
GROUP BY DATE_TRUNC('hour', started_at)
ORDER BY hour DESC;

COMMENT ON TABLE daily_limits IS 'Stores daily transaction limits - the hot table causing contention';
COMMENT ON TABLE sync_history IS 'Audit trail of cache-to-DB synchronization events';
COMMENT ON TABLE transaction_log IS 'Detailed log of all limit consumption transactions';
COMMENT ON TABLE performance_metrics IS 'Periodic snapshots of system performance metrics';
