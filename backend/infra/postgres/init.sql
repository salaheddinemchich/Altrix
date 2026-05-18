-- Run once by docker-entrypoint-initdb.d when the container first starts.
-- Creates separate schemas reserved for each service (currently unused —
-- all tables live in the default public schema; kept for future isolation).
-- NOTE: GRANT statements for a "migrator" role were removed — that role was
-- never created and is not used by any service.  The altrix superuser that
-- Spring Boot connects as already has full access to the database.

CREATE SCHEMA IF NOT EXISTS project_service;
CREATE SCHEMA IF NOT EXISTS job_service;
