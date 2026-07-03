-- User-selectable messaging implementation for a Jakarta EE migration target.
-- NATIVE_KAFKA_CLIENTS = raw kafka-clients (default, today's only behavior);
-- SPRING_KAFKA_HYBRID = spring-kafka bridged manually into the CDI container.
-- Ignored for Spring Boot sources.
ALTER TABLE migration_jobs
    ADD COLUMN IF NOT EXISTS jakarta_messaging_target VARCHAR(24) NOT NULL DEFAULT 'NATIVE_KAFKA_CLIENTS';
