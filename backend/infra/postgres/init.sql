-- Run once by docker-entrypoint-initdb.d when the container first starts.
-- Creates separate schemas for each service so Flyway histories don't collide.

CREATE SCHEMA IF NOT EXISTS project_service;
CREATE SCHEMA IF NOT EXISTS job_service;

GRANT ALL ON SCHEMA project_service TO migrator;
GRANT ALL ON SCHEMA job_service     TO migrator;
