-- ===========================================
-- Limit Cache POC - Seed Data
-- ===========================================

-- Generate daily limits for current month and next month
-- Each day starts with a limit of $10,000 (1,000,000 cents)

DO $$
DECLARE
    v_start_date DATE;
    v_end_date DATE;
    v_current_date DATE;
    v_limit BIGINT;
BEGIN
    -- Current month
    v_start_date := DATE_TRUNC('month', CURRENT_DATE);
    v_end_date := (DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '2 months')::DATE;
    v_current_date := v_start_date;
    
    WHILE v_current_date < v_end_date LOOP
        -- Vary the limit slightly for realism (weekends have lower limits)
        IF EXTRACT(DOW FROM v_current_date) IN (0, 6) THEN
            v_limit := 500000;  -- $5,000 on weekends
        ELSE
            v_limit := 1000000; -- $10,000 on weekdays
        END IF;
        
        INSERT INTO daily_limits (day_date, initial_limit, remaining, consumed, transaction_count)
        VALUES (v_current_date, v_limit, v_limit, 0, 0)
        ON CONFLICT (day_date) DO NOTHING;
        
        v_current_date := v_current_date + INTERVAL '1 day';
    END LOOP;
    
    RAISE NOTICE 'Seeded daily limits from % to %', v_start_date, v_end_date - INTERVAL '1 day';
END $$;

-- Insert some historical sync data for demo purposes
INSERT INTO sync_history (sync_type, records_synced, duration_ms, status, started_at, completed_at)
VALUES 
    ('STARTUP', 31, 45, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '1 hour', CURRENT_TIMESTAMP - INTERVAL '1 hour' + INTERVAL '45 milliseconds'),
    ('SCHEDULED', 5, 12, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '30 minutes', CURRENT_TIMESTAMP - INTERVAL '30 minutes' + INTERVAL '12 milliseconds'),
    ('SCHEDULED', 8, 15, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '25 minutes', CURRENT_TIMESTAMP - INTERVAL '25 minutes' + INTERVAL '15 milliseconds'),
    ('SCHEDULED', 3, 8, 'SUCCESS', CURRENT_TIMESTAMP - INTERVAL '20 minutes', CURRENT_TIMESTAMP - INTERVAL '20 minutes' + INTERVAL '8 milliseconds');

-- Verify seed data
DO $$
DECLARE
    v_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO v_count FROM daily_limits;
    RAISE NOTICE 'Total daily_limits records: %', v_count;
    
    SELECT COUNT(*) INTO v_count FROM daily_limits WHERE remaining = initial_limit;
    RAISE NOTICE 'Unused limits (fresh): %', v_count;
END $$;

-- Show sample data
SELECT 
    day_date,
    CASE WHEN EXTRACT(DOW FROM day_date) IN (0, 6) THEN 'Weekend' ELSE 'Weekday' END as day_type,
    '$' || (initial_limit / 100.0)::TEXT as limit_dollars,
    remaining,
    transaction_count
FROM daily_limits
WHERE day_date >= CURRENT_DATE
ORDER BY day_date
LIMIT 14;
