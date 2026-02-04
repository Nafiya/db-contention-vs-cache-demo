# 🚀 SQL DB CPU Performance Enhancement POC

## Problem Statement

In high-throughput financial systems, daily transaction limits are stored in a SQL database. When multiple concurrent transactions need to:
1. **READ** the current limit for specific days
2. **UPDATE** (decrement) the limit after processing

This creates a **hot-spot contention** problem:
- All transactions fight for the same 30 rows (days in a month)
- Row-level locks cause serialization
- Database CPU spikes to 100%
- Throughput collapses under load

```
┌─────────────────────────────────────────────────────────────────┐
│                    THE PROBLEM                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Transaction 1 ──┐                                              │
│   Transaction 2 ──┼──► [Row Lock: Day 15] ──► DB CPU: 100%      │
│   Transaction 3 ──┤         ↓                                    │
│   Transaction N ──┘    Serialization                             │
│                                                                  │
│   Result: ~100 TPS, High Latency, Timeouts                      │
└─────────────────────────────────────────────────────────────────┘
```

## Solution: Redis Cache Layer

```
┌─────────────────────────────────────────────────────────────────┐
│                    THE SOLUTION                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Transaction 1 ──┐      ┌─────────┐      ┌──────────┐          │
│   Transaction 2 ──┼──►   │  Redis  │ ───► │ Postgres │          │
│   Transaction 3 ──┤      │ (Cache) │      │  (Async) │          │
│   Transaction N ──┘      └─────────┘      └──────────┘          │
│                              ↑                  ↑                │
│                         Atomic Ops        Periodic Sync          │
│                         (DECRBY)          (Every 5s)             │
│                                                                  │
│   Result: ~10,000+ TPS, Low Latency, DB CPU: <20%               │
└─────────────────────────────────────────────────────────────────┘
```

## Architecture

### Components

1. **PostgreSQL** - Source of truth for daily limits
2. **Redis** - High-performance cache with atomic operations
3. **Spring Boot Service** - Orchestrates caching strategy
4. **Sync Scheduler** - Periodically syncs cache → DB

### Data Flow

```
1. Application Startup:
   DB (daily_limits) ──► Redis (HASH: limits:2024:01)

2. Transaction Processing:
   Request ──► Check Redis ──► DECRBY (atomic) ──► Response
                                    ↓
                            Track dirty keys

3. Periodic Sync (every 5 seconds):
   Redis dirty keys ──► Batch UPDATE ──► PostgreSQL
   
4. Cache Miss:
   Request ──► Redis MISS ──► Load from DB ──► Cache ──► Process
```

## Key Features

### 1. Atomic Operations in Redis
```redis
HGET limits:2024:01 day_15           # Get current limit
HINCRBY limits:2024:01 day_15 -100   # Atomic decrement
```

### 2. Optimistic Locking (Optional DB writes)
```sql
UPDATE daily_limits 
SET remaining = remaining - :delta, version = version + 1
WHERE day_date = :date AND version = :expectedVersion
```

### 3. Write-Behind Caching
- Transactions update Redis immediately
- Background job batches DB updates
- Reduces DB writes by 95%+

### 4. Circuit Breaker
- Falls back to DB if Redis unavailable
- Graceful degradation

## Metrics Tracked

| Metric | Without Cache | With Cache |
|--------|---------------|------------|
| Throughput | ~100 TPS | ~10,000 TPS |
| Avg Latency | 50-200ms | 1-5ms |
| DB CPU | 95-100% | 10-20% |
| DB Connections | Maxed out | Minimal |

## Quick Start

```bash
# Start infrastructure
cd docker
docker-compose up -d

# Run the application
cd ../backend
./mvnw spring-boot:run

# Run load test
cd ../scripts
./load-test.sh
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/limits/{year}/{month}` | GET | Get all limits for month |
| `/api/limits/{year}/{month}/{day}` | GET | Get limit for specific day |
| `/api/limits/consume` | POST | Consume limit (transaction) |
| `/api/limits/sync` | POST | Force sync to DB |
| `/api/metrics` | GET | Performance metrics |
| `/api/demo/load-test` | POST | Run load test |

## Project Structure

```
limit-cache-poc/
├── backend/
│   └── src/main/java/com/limitcache/
│       ├── config/          # Redis, DB, Scheduler config
│       ├── model/           # Entity & DTOs
│       ├── repository/      # JPA repositories
│       ├── service/         # Business logic & caching
│       ├── controller/      # REST endpoints
│       └── scheduler/       # Sync jobs
├── docker/
│   ├── docker-compose.yml
│   └── init-scripts/        # DB initialization
├── scripts/
│   └── load-test.sh
└── docs/
    └── architecture.md
```

## Technologies

- **Java 17** + **Spring Boot 3.2**
- **PostgreSQL 15** - Primary database
- **Redis 7** - Caching layer
- **Spring Data JPA** - ORM
- **Spring Data Redis** - Cache operations
- **Micrometer** - Metrics
- **Docker Compose** - Local development
