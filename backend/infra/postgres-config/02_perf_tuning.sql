-- ──────────────────────────────────────────────────────────────────────────────
-- Altrix — PostgreSQL performance tuning (dev / Docker Compose)
--
-- Executed once by docker-entrypoint-initdb.d on first container start.
-- ALTER SYSTEM writes to postgresql.auto.conf which takes precedence over
-- postgresql.conf.  pg_reload_conf() applies changes in the same session.
--
-- Target: 4 GB Docker allocation on SSD host.
-- For AWS RDS: configure equivalent settings via a custom Parameter Group.
-- ──────────────────────────────────────────────────────────────────────────────

-- ── Memory ────────────────────────────────────────────────────────────────────
-- ~25% of available RAM for the Postgres page cache
ALTER SYSTEM SET shared_buffers             = '1GB';
-- Per-sort / per-hash operation.  16 MB × 50 connections = 800 MB worst-case.
ALTER SYSTEM SET work_mem                   = '16MB';
-- For VACUUM, CREATE INDEX — larger = faster index builds
ALTER SYSTEM SET maintenance_work_mem       = '256MB';
-- Planner hint: how much RAM the OS uses for disk caching (no allocation)
ALTER SYSTEM SET effective_cache_size       = '3GB';

-- ── I/O (SSD-tuned) ──────────────────────────────────────────────────────────
-- Default 4.0 = spinning disk.  SSD: 1.0–1.2 → planner prefers index scans.
ALTER SYSTEM SET random_page_cost           = '1.1';
-- Concurrent I/O operations the OS can handle on SSD
ALTER SYSTEM SET effective_io_concurrency   = '200';

-- ── WAL / Checkpoints ────────────────────────────────────────────────────────
ALTER SYSTEM SET wal_buffers                = '64MB';
-- Spread checkpoint I/O over 90% of the timeout window — reduces spikes
ALTER SYSTEM SET checkpoint_completion_target = '0.9';

-- ── Connections ───────────────────────────────────────────────────────────────
-- Low for local dev — Hikari pools reuse connections efficiently
ALTER SYSTEM SET max_connections            = '100';

-- ── Query planner ────────────────────────────────────────────────────────────
-- More histogram samples per column → better row estimates for enum columns
ALTER SYSTEM SET default_statistics_target  = '100';

-- ── Parallel query (pgvector benefits from this) ──────────────────────────────
ALTER SYSTEM SET max_parallel_workers_per_gather = '2';
ALTER SYSTEM SET max_parallel_workers            = '4';

-- ── Logging — log slow queries for visibility during dev / staging ────────────
-- Set to -1 to disable in prod RDS (use CloudWatch slow-query logs instead)
ALTER SYSTEM SET log_min_duration_statement = '500';

-- Apply all settings immediately (takes effect for new connections)
SELECT pg_reload_conf();
